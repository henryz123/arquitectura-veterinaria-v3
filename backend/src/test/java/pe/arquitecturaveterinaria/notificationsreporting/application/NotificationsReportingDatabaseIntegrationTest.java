package pe.arquitecturaveterinaria.notificationsreporting.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.annotation.Transactional;

import pe.arquitecturaveterinaria.identityaccess.application.IdentityAccessService;
import pe.arquitecturaveterinaria.identityaccess.application.SessionPrincipal;
import pe.arquitecturaveterinaria.identityaccess.infrastructure.TenantContextJdbc;
import pe.arquitecturaveterinaria.inventorysales.application.CatalogProduct;
import pe.arquitecturaveterinaria.inventorysales.application.CatalogProductDraft;
import pe.arquitecturaveterinaria.inventorysales.application.CatalogService;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.DeliveryMode;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.Draft;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.LineDraft;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.Order;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderService;
import pe.arquitecturaveterinaria.inventorysales.application.StockAdjustmentDraft;
import pe.arquitecturaveterinaria.inventorysales.application.StockService;
import pe.arquitecturaveterinaria.notificationsreporting.infrastructure.NotificationsReportingJdbcRepository;
import pe.arquitecturaveterinaria.shared.api.ApiException;

/**
 * B06-B proof against the real local PostgreSQL schema. The test transaction
 * rolls back its B06-B synthetic events and notification state after each case.
 */
@SpringBootTest
@Transactional(transactionManager = "jdbcTransactionManager")
class NotificationsReportingDatabaseIntegrationTest {

	@Autowired private IdentityAccessService identityAccessService;
	@Autowired private CatalogService catalogService;
	@Autowired private StockService stockService;
	@Autowired private OnlineOrderService onlineOrderService;
	@Autowired private AuditTrailService auditTrailService;
	@Autowired private InternalNotificationService notifications;
	@Autowired private NotificationsReportingJdbcRepository notificationRepository;
	@Autowired private TenantContextJdbc tenantContext;
	@Autowired private JdbcTemplate jdbc;

	@Test
	void bindsTheTenantRlsContextOnTheJdbcTransaction() {
		SessionPrincipal client = auroraClient();
		tenantContext.setTenantId(client.session().tenantId());
		assertEquals(client.session().tenantId().toString(),
				jdbc.queryForObject("select current_setting('app.tenant_id', true)", String.class));
		int inserted = jdbc.update("""
				insert into notifications_reporting.notification_delivery (
				  tenant_id, recipient_principal_id, source_module, source_event_id,
				  source_resource_type, source_resource_id, notification_code
				) values (?, ?, 'B05', ?, 'B06_TEST', ?, 'B06_CONTEXT_CHECK')
				""", client.session().tenantId(), client.session().accountId(), UUID.randomUUID(), UUID.randomUUID());
		assertEquals(1, inserted);
	}

	@Test
	void projectsOriginalB04AndB05EventsForOnlyTheTenantAdministrator() {
		SessionPrincipal administrator = administrator();
		SessionPrincipal client = auroraClient();
		String suffix = UUID.randomUUID().toString().substring(0, 8);
		CatalogProduct product = catalogService.createProduct(administrator,
				new CatalogProductDraft(null, "B06-AUDIT-" + suffix, "B06-A-E2E producto " + suffix, null,
						new BigDecimal("18.00"), "ACTIVO_PUBLICADO", true, null));
		stockService.adjust(administrator, product.id(), new StockAdjustmentDraft(2, "B06-A-E2E saldo", UUID.randomUUID()));
		onlineOrderService.updateDeliveryConfiguration(administrator, "Recojo B06-A-E2E", false, BigDecimal.ZERO, null);
		Order order = onlineOrderService.create(client, new Draft(UUID.randomUUID(), DeliveryMode.RECOJO, "999 060 001", null,
				null, List.of(new LineDraft(product.id(), 1, new BigDecimal("18.00")))));

		AuditTrailPage page = auditTrailService.list(administrator, LocalDate.now().minusDays(1), LocalDate.now(), "ALL", null, 0, 50);
		assertTrue(page.events().stream().anyMatch(event -> event.sourceModule().equals("B04") && event.resourceId().equals(product.id())));
		assertTrue(page.events().stream().anyMatch(event -> event.sourceModule().equals("B05") && event.resourceId().equals(order.id())));
		assertTrue(page.events().stream().allMatch(event -> event.actionCode() != null && event.sourceEventId() != null));

		ApiException forbidden = assertThrows(ApiException.class,
				() -> auditTrailService.list(client, LocalDate.now().minusDays(1), LocalDate.now(), "ALL", null, 0, 25));
		assertEquals(HttpStatus.FORBIDDEN, forbidden.status());
		assertEquals("B06_AUDIT_ADMIN_REQUIRED", forbidden.code());
	}

	@Test
	void persistsRecipientScopedNotificationsRetriesFailureAndDoesNotDuplicateAnIdempotentOrder() {
		SessionPrincipal administrator = administrator();
		SessionPrincipal clientA = auroraClient();
		SessionPrincipal clientB = secondAuroraClient();
		String suffix = UUID.randomUUID().toString().substring(0, 8);
		CatalogProduct product = catalogService.createProduct(administrator,
				new CatalogProductDraft(null, "B06-NOTIFY-" + suffix, "B06-A-E2E notificación " + suffix, null,
						new BigDecimal("11.00"), "ACTIVO_PUBLICADO", true, null));
		stockService.adjust(administrator, product.id(), new StockAdjustmentDraft(2, "B06-A-E2E saldo", UUID.randomUUID()));
		onlineOrderService.updateDeliveryConfiguration(administrator, "Recojo B06-A-E2E", false, BigDecimal.ZERO, null);
		Draft draft = new Draft(UUID.randomUUID(), DeliveryMode.RECOJO, "999 060 002", null, null,
				List.of(new LineDraft(product.id(), 1, new BigDecimal("11.00"))));
		Order order = onlineOrderService.create(clientA, draft);
		assertEquals(order.id(), onlineOrderService.create(clientA, draft).id());
		assertTrue(notifications.listOwn(administrator).stream().anyMatch(item -> item.sourceResourceId().equals(order.id())
				&& "ONLINE_ORDER_CREATED".equals(item.notificationCode())));

		InternalNotificationService failingDelivery = new InternalNotificationService(notificationRepository, tenantContext,
				item -> { throw new IllegalStateException("Fallo B06-A-E2E controlado"); });
		failingDelivery.listOwn(clientA);
		assertEquals("FAILED", notificationStatus(order.id()));
		assertEquals(1, notificationAttempts(order.id()));

		List<InternalNotification> delivered = notifications.listOwn(clientA).stream()
				.filter(item -> item.sourceResourceId().equals(order.id())).toList();
		assertEquals(1, delivered.size());
		assertEquals("ONLINE_ORDER_CREATED", delivered.getFirst().notificationCode());
		assertEquals("ACCEPTED_LOCAL", notificationStatus(order.id()));
		assertEquals(2, notificationAttempts(order.id()));

		assertFalse(notifications.listOwn(clientB).stream().anyMatch(item -> item.sourceResourceId().equals(order.id())));
		InternalNotification read = notifications.markRead(clientA, delivered.getFirst().id());
		assertTrue(read.readAt() != null);
		ApiException foreignNotification = assertThrows(ApiException.class,
				() -> notifications.markRead(clientB, delivered.getFirst().id()));
		assertEquals(HttpStatus.NOT_FOUND, foreignNotification.status());
	}

	@Test
	void exposesOnlyTheAuditedProjectionAndTenantLocalRecipientFieldsToTheApplicationRole() {
		SessionPrincipal administrator = administrator();
		tenantContext.setTenantId(administrator.session().tenantId());
		assertFalse(notificationRepository.listActiveStaffRecipients(List.of("ADMIN_VETERINARIA", "CAJERO")).isEmpty());
		assertThrows(DataAccessException.class,
				() -> jdbc.queryForObject("select reason from tenant_admin.audit_entry limit 1", String.class));
		assertThrows(DataAccessException.class,
				() -> jdbc.queryForObject("select normalized_email from identity_auth.account_profile limit 1", String.class));
	}

	private String notificationStatus(UUID orderId) {
		String status = jdbc.queryForObject("""
				select status from notifications_reporting.notification_delivery
				where source_resource_id = ? and notification_code = 'ONLINE_ORDER_CREATED'
				""", String.class, orderId);
		Assertions.assertNotNull(status);
		return status;
	}

	private int notificationAttempts(UUID orderId) {
		Integer attempts = jdbc.queryForObject("""
				select attempt_count from notifications_reporting.notification_delivery
				where source_resource_id = ? and notification_code = 'ONLINE_ORDER_CREATED'
				""", Integer.class, orderId);
		Assertions.assertNotNull(attempts);
		return attempts;
	}

	private SessionPrincipal administrator() {
		return new SessionPrincipal(identityAccessService.authenticateStaff("veterinaria-aurora", "administracion@demo.invalid",
				required("AV_DEMO_AURORA_PASSWORD")).context());
	}

	private SessionPrincipal auroraClient() {
		return client("AV_DEMO_CLIENT_AURORA_EMAIL", "AV_DEMO_CLIENT_AURORA_PASSWORD");
	}

	private SessionPrincipal secondAuroraClient() {
		return client("AV_B05D_CLIENT_AURORA_B_EMAIL", "AV_B05D_CLIENT_AURORA_B_PASSWORD");
	}

	private SessionPrincipal client(String emailVariable, String passwordVariable) {
		return new SessionPrincipal(identityAccessService.authenticateClient("veterinaria-aurora", required(emailVariable),
				required(passwordVariable)).context());
	}

	private String required(String name) {
		String value = System.getenv(name);
		Assertions.assertNotNull(value, "La prueba requiere la variable local " + name + ".");
		Assertions.assertFalse(value.isBlank(), "La variable local " + name + " no puede estar vacía.");
		return value;
	}
}
