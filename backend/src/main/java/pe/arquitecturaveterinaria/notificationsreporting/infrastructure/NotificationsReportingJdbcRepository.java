package pe.arquitecturaveterinaria.notificationsreporting.infrastructure;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.stereotype.Repository;

import pe.arquitecturaveterinaria.notificationsreporting.application.AuditTrailEvent;
import pe.arquitecturaveterinaria.notificationsreporting.application.InternalNotification;
import pe.arquitecturaveterinaria.notificationsreporting.application.NotificationDeliveryRequest;
import pe.arquitecturaveterinaria.notificationsreporting.application.NotificationDeliveryWorkItem;

/** JDBC adapter. Tenant/principal RLS context is set by the application service. */
@Repository
public class NotificationsReportingJdbcRepository {
	private final JdbcTemplate jdbc;

	public NotificationsReportingJdbcRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public List<AuditTrailEvent> listAuditEvents(LocalDate from, LocalDate to, String sourceModule, String actionCode,
			int offset, int limit) {
		return jdbc.query("""
				with events as (
				    select 'B01'::varchar as source_module, id as source_event_id, action_code, resource_type, resource_id,
				           created_at as occurred_at, actor_principal_id, outcome
				    from tenant_admin.audit_entry
				    union all
				    select 'B02'::varchar, id, action_code, resource_type, resource_id, occurred_at, actor_principal_id, null::varchar
				    from owner_pet_care.care_audit_event
				    union all
				    select 'B03'::varchar, id, action_code, 'SCHEDULE_REQUEST'::varchar, request_id, created_at, actor_principal_id, null::varchar
				    from appointment_management.schedule_change_event
				    union all
				    select 'B04'::varchar, id, movement_type, 'PRODUCT'::varchar, product_id, created_at, actor_principal_id, null::varchar
				    from inventory_sales.stock_movement
				    union all
				    select 'B05'::varchar, id, status, 'ONLINE_ORDER'::varchar, order_id, created_at, actor_principal_id, null::varchar
				    from inventory_sales.online_order_status_event
				)
				select source_module, source_event_id, action_code, resource_type, resource_id, occurred_at, actor_principal_id, outcome
				from events
				where occurred_at >= (cast(? as date)::timestamp at time zone 'America/Lima')
				  and occurred_at < ((cast(? as date) + 1)::timestamp at time zone 'America/Lima')
				  and (cast(? as varchar) = 'ALL' or source_module = cast(? as varchar))
				  and (cast(? as varchar) is null or action_code = cast(? as varchar))
				order by occurred_at desc, source_module, source_event_id
				limit ? offset ?
				""", this::mapAuditEvent, from, to, sourceModule, sourceModule, actionCode, actionCode, limit, offset);
	}

	public void enqueue(NotificationDeliveryRequest request) {
		/*
		 * The tenant GUC is transaction-local. Set it and insert through one JDBC
		 * ConnectionCallback so the INSERT cannot accidentally run on a different
		 * pooled connection. The recipient GUC is also required because PostgreSQL
		 * applies the recipient SELECT policy while resolving ON CONFLICT. The
		 * surrounding service requires an existing business transaction; RLS remains
		 * the database authority.
		 */
		jdbc.execute((ConnectionCallback<Void>) connection -> {
			String previousPrincipalId = currentPrincipalId(connection);
			try {
				try (PreparedStatement scope = connection.prepareStatement(
						"select set_config('app.tenant_id', ?, true), set_config('app.principal_id', ?, true)")) {
					scope.setString(1, request.tenantId().toString());
					scope.setString(2, request.recipientPrincipalId().toString());
					scope.execute();
				}
				try (PreparedStatement scopeCheck = connection.prepareStatement(
						"select current_setting('app.tenant_id', true) = ? and current_setting('app.principal_id', true) = ?")) {
					scopeCheck.setString(1, request.tenantId().toString());
					scopeCheck.setString(2, request.recipientPrincipalId().toString());
					try (ResultSet result = scopeCheck.executeQuery()) {
						if (!result.next() || !result.getBoolean(1)) {
							throw new IllegalStateException("No se pudo establecer el ámbito tenant de la notificación.");
						}
					}
				}
				try (PreparedStatement insert = connection.prepareStatement("""
						insert into notifications_reporting.notification_delivery (
						    tenant_id, recipient_principal_id, source_module, source_event_id,
						    source_resource_type, source_resource_id, notification_code
						) values (?, ?, ?, ?, ?, ?, ?)
						on conflict (tenant_id, recipient_principal_id, source_module, source_event_id, notification_code) do nothing
						""")) {
					insert.setObject(1, request.tenantId());
					insert.setObject(2, request.recipientPrincipalId());
					insert.setString(3, request.sourceModule());
					insert.setObject(4, request.sourceEventId());
					insert.setString(5, request.sourceResourceType());
					insert.setObject(6, request.sourceResourceId());
					insert.setString(7, request.notificationCode());
					insert.executeUpdate();
				}
			} finally {
				restorePrincipalId(connection, previousPrincipalId);
			}
			return null;
		});
	}

	public List<UUID> listActiveStaffRecipients(Collection<String> roleCodes) {
		if (roleCodes == null || roleCodes.isEmpty()) {
			return List.of();
		}
		String placeholders = String.join(", ", java.util.Collections.nCopies(roleCodes.size(), "?"));
		return jdbc.query("""
				select distinct account_profile.principal_id
				from identity_auth.account_profile account_profile
				join identity_auth.role_assignment role_assignment
				  on role_assignment.tenant_id = account_profile.tenant_id
				 and role_assignment.staff_principal_id = account_profile.principal_id
				where account_profile.account_type = 'STAFF'
				  and account_profile.status = 'ACTIVA'
				  and role_assignment.revoked_at is null
				  and role_assignment.role_code in (""" + placeholders + ")\n\t\t\t\torder by account_profile.principal_id",
				(resultSet, rowNumber) -> resultSet.getObject(1, UUID.class), roleCodes.toArray());
	}

	public List<NotificationDeliveryWorkItem> claimDueForCurrentRecipient(int limit) {
		return jdbc.query("""
				select id, source_module, source_event_id, source_resource_type, source_resource_id, notification_code
				from notifications_reporting.notification_delivery
				where status in ('PENDING', 'FAILED') and next_attempt_at <= statement_timestamp()
				order by next_attempt_at, created_at, id
				for update skip locked
				limit ?
				""", this::mapWorkItem, limit);
	}

	public void markAcceptedLocal(UUID id) {
		jdbc.update("""
				update notifications_reporting.notification_delivery
				set status = 'ACCEPTED_LOCAL', attempt_count = attempt_count + 1,
				    last_attempt_at = statement_timestamp(), accepted_local_at = statement_timestamp(),
				    last_failure_code = null
				where id = ? and status in ('PENDING', 'FAILED')
				""", id);
	}

	public void markFailed(UUID id, String failureCode) {
		jdbc.update("""
				update notifications_reporting.notification_delivery
				set status = 'FAILED', attempt_count = attempt_count + 1,
				    last_attempt_at = statement_timestamp(), next_attempt_at = statement_timestamp(),
				    last_failure_code = ?
				where id = ? and status in ('PENDING', 'FAILED')
				""", failureCode, id);
	}

	public List<InternalNotification> listAcceptedForCurrentRecipient() {
		return jdbc.query("""
				select id, source_module, source_event_id, source_resource_type, source_resource_id,
				       notification_code, accepted_local_at, read_at, created_at
				from notifications_reporting.notification_delivery
				where status = 'ACCEPTED_LOCAL'
				order by created_at desc, id desc
				limit 100
				""", this::mapInternalNotification);
	}

	public Optional<InternalNotification> markRead(UUID id) {
		return jdbc.query("""
				update notifications_reporting.notification_delivery
				set read_at = coalesce(read_at, statement_timestamp())
				where id = ? and status = 'ACCEPTED_LOCAL'
				returning id, source_module, source_event_id, source_resource_type, source_resource_id,
				          notification_code, accepted_local_at, read_at, created_at
				""", this::mapInternalNotification, id).stream().findFirst();
	}

	private AuditTrailEvent mapAuditEvent(ResultSet resultSet, int rowNumber) throws SQLException {
		return new AuditTrailEvent(resultSet.getString("source_module"), resultSet.getObject("source_event_id", UUID.class),
				resultSet.getString("action_code"), resultSet.getString("resource_type"), resultSet.getObject("resource_id", UUID.class),
				instant(resultSet, "occurred_at"), resultSet.getObject("actor_principal_id", UUID.class), resultSet.getString("outcome"));
	}

	private NotificationDeliveryWorkItem mapWorkItem(ResultSet resultSet, int rowNumber) throws SQLException {
		return new NotificationDeliveryWorkItem(resultSet.getObject("id", UUID.class), resultSet.getString("source_module"),
				resultSet.getObject("source_event_id", UUID.class), resultSet.getString("source_resource_type"),
				resultSet.getObject("source_resource_id", UUID.class), resultSet.getString("notification_code"));
	}

	private InternalNotification mapInternalNotification(ResultSet resultSet, int rowNumber) throws SQLException {
		return new InternalNotification(resultSet.getObject("id", UUID.class), resultSet.getString("source_module"),
				resultSet.getObject("source_event_id", UUID.class), resultSet.getString("source_resource_type"),
				resultSet.getObject("source_resource_id", UUID.class), resultSet.getString("notification_code"),
				nullableInstant(resultSet, "accepted_local_at"), nullableInstant(resultSet, "read_at"), instant(resultSet, "created_at"));
	}

	private Instant instant(ResultSet resultSet, String column) throws SQLException {
		return resultSet.getTimestamp(column).toInstant();
	}

	private Instant nullableInstant(ResultSet resultSet, String column) throws SQLException {
		var value = resultSet.getTimestamp(column);
		return value == null ? null : value.toInstant();
	}

	private String currentPrincipalId(java.sql.Connection connection) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("select current_setting('app.principal_id', true)")) {
			try (ResultSet result = statement.executeQuery()) {
				return result.next() ? result.getString(1) : null;
			}
		}
	}

	private void restorePrincipalId(java.sql.Connection connection, String previousPrincipalId) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("select set_config('app.principal_id', ?, true)")) {
			statement.setString(1, previousPrincipalId == null ? "" : previousPrincipalId);
			statement.execute();
		}
	}
}
