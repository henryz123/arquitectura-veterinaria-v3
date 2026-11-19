package pe.arquitecturaveterinaria.notificationsreporting.application;

import java.util.List;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import pe.arquitecturaveterinaria.identityaccess.application.SessionPrincipal;
import pe.arquitecturaveterinaria.identityaccess.infrastructure.TenantContextJdbc;
import pe.arquitecturaveterinaria.notificationsreporting.infrastructure.NotificationsReportingJdbcRepository;

/**
 * V1 in-account notification queue. Source operations enqueue atomically; a
 * later recipient-scoped read performs delivery independently of that operation.
 */
@Service
public class InternalNotificationService {
	private static final Set<String> SCHEDULING_RECIPIENT_ROLES = Set.of("ADMIN_VETERINARIA", "RECEPCION");
	private static final Set<String> COMMERCIAL_RECIPIENT_ROLES = Set.of("ADMIN_VETERINARIA", "CAJERO");
	private static final int DELIVERY_BATCH_SIZE = 50;
	private static final String DELIVERY_FAILURE_CODE = "IN_APP_DELIVERY_FAILED";
	private final NotificationsReportingJdbcRepository repository;
	private final TenantContextJdbc tenantContext;
	private final InAppNotificationDeliveryPort deliveryPort;
	private final NotificationsReportingAccessPolicy accessPolicy = new NotificationsReportingAccessPolicy();

	public InternalNotificationService(NotificationsReportingJdbcRepository repository, TenantContextJdbc tenantContext,
			InAppNotificationDeliveryPort deliveryPort) {
		this.repository = repository;
		this.tenantContext = tenantContext;
		this.deliveryPort = deliveryPort;
	}

	/** Called from the source aggregate transaction after its authoritative event was written. */
	@Transactional(transactionManager = "jdbcTransactionManager", propagation = Propagation.MANDATORY)
	public void enqueue(NotificationDeliveryRequest request) {
		// The command originates inside a tenant-scoped business transaction. It
		// must never open an independent transaction that could outlive the event.
		tenantContext.setTenantId(request.tenantId());
		repository.enqueue(request);
	}

	/** Resolves only active STAFF accounts visible through the existing tenant RLS policy. */
	@Transactional(transactionManager = "jdbcTransactionManager", propagation = Propagation.MANDATORY)
	public List<UUID> activeSchedulingRecipients(UUID tenantId) {
		tenantContext.setTenantId(tenantId);
		return repository.listActiveStaffRecipients(SCHEDULING_RECIPIENT_ROLES);
	}

	@Transactional(transactionManager = "jdbcTransactionManager", propagation = Propagation.MANDATORY)
	public List<UUID> activeCommercialRecipients(UUID tenantId) {
		tenantContext.setTenantId(tenantId);
		return repository.listActiveStaffRecipients(COMMERCIAL_RECIPIENT_ROLES);
	}

	/** Emits one idempotent delivery per distinct, authorized recipient. */
	@Transactional(transactionManager = "jdbcTransactionManager", propagation = Propagation.MANDATORY)
	public void enqueueForRecipients(UUID tenantId, Collection<UUID> recipientPrincipalIds, String sourceModule, UUID sourceEventId,
			String sourceResourceType, UUID sourceResourceId, String notificationCode) {
		tenantContext.setTenantId(tenantId);
		if (recipientPrincipalIds == null) {
			return;
		}
		recipientPrincipalIds.stream().filter(java.util.Objects::nonNull).distinct()
				.forEach(recipient -> repository.enqueue(new NotificationDeliveryRequest(tenantId, recipient, sourceModule,
						sourceEventId, sourceResourceType, sourceResourceId, notificationCode)));
	}

	@Transactional(transactionManager = "jdbcTransactionManager")
	public List<InternalNotification> listOwn(SessionPrincipal actor) {
		accessPolicy.requireNotificationRecipient(actor);
		setRecipientContext(actor);
		deliverDueForCurrentRecipient();
		return repository.listAcceptedForCurrentRecipient();
	}

	@Transactional(transactionManager = "jdbcTransactionManager")
	public InternalNotification markRead(SessionPrincipal actor, UUID notificationId) {
		accessPolicy.requireNotificationRecipient(actor);
		setRecipientContext(actor);
		return repository.markRead(notificationId).orElseThrow(() -> new pe.arquitecturaveterinaria.shared.api.ApiException(
				org.springframework.http.HttpStatus.NOT_FOUND, "B06_NOTIFICATION_NOT_FOUND", "No se encontró la notificación solicitada."));
	}

	private void setRecipientContext(SessionPrincipal actor) {
		tenantContext.setTenantId(actor.session().tenantId());
		tenantContext.setPrincipalId(actor.session().accountId());
	}

	private void deliverDueForCurrentRecipient() {
		for (NotificationDeliveryWorkItem item : repository.claimDueForCurrentRecipient(DELIVERY_BATCH_SIZE)) {
			try {
				deliveryPort.accept(item);
				repository.markAcceptedLocal(item.id());
			} catch (RuntimeException exception) {
				repository.markFailed(item.id(), DELIVERY_FAILURE_CODE);
			}
		}
	}
}
