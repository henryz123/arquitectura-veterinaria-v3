package pe.arquitecturaveterinaria.inventorysales.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

import pe.arquitecturaveterinaria.identityaccess.application.IdentityAccessService;
import pe.arquitecturaveterinaria.identityaccess.application.SessionPrincipal;
import pe.arquitecturaveterinaria.identityaccess.application.TenantSession;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.DeliveryMode;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.Draft;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.LineDraft;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.Order;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.StaffOrder;
import pe.arquitecturaveterinaria.shared.api.ApiException;

/** B05-C1 confirmation stays atomic, tenant-local and idempotent against the B04 stock ledger. */
@SpringBootTest
@Transactional(transactionManager = "jdbcTransactionManager")
class OnlineOrderConfirmationDatabaseIntegrationTest {

	@Autowired private CatalogService catalogService;
	@Autowired private StockService stockService;
	@Autowired private OnlineOrderService onlineOrderService;
	@Autowired private IdentityAccessService identityAccessService;

	@Test
	void confirmsAllLinesOnceAndPreservesTheirAcceptedSnapshots() {
		SessionPrincipal admin = administrator("veterinaria-aurora", "AV_DEMO_AURORA_PASSWORD");
		SessionPrincipal client = client("veterinaria-aurora", "AV_DEMO_CLIENT_AURORA_EMAIL", "AV_DEMO_CLIENT_AURORA_PASSWORD");
		String suffix = UUID.randomUUID().toString().substring(0, 8);
		CatalogProduct first = product(admin, suffix + "A", new BigDecimal("12.50"), 4);
		CatalogProduct second = product(admin, suffix + "B", new BigDecimal("7.25"), 3);
		Order order = onlineOrderService.create(client, draft(first, 2, second, 1));
		int firstBefore = balance(admin, first.id());
		int secondBefore = balance(admin, second.id());
		int firstMovements = stockService.listMovements(admin, first.id()).size();
		int secondMovements = stockService.listMovements(admin, second.id()).size();

		StaffOrder confirmed = onlineOrderService.confirm(admin, order.id());
		assertEquals("CONFIRMADO", confirmed.status());
		assertEquals(new BigDecimal("32.25"), confirmed.totalPen());
		assertEquals(2, confirmed.lines().size());
		assertEquals(firstBefore - 2, balance(admin, first.id()));
		assertEquals(secondBefore - 1, balance(admin, second.id()));
		assertEquals(firstMovements + 1, stockService.listMovements(admin, first.id()).size());
		assertEquals(secondMovements + 1, stockService.listMovements(admin, second.id()).size());
		assertEquals(1, confirmed.history().stream().filter(event -> "CONFIRMADO".equals(event.status())).count());

		StaffOrder replay = onlineOrderService.confirm(admin, order.id());
		assertEquals("CONFIRMADO", replay.status());
		assertEquals(firstBefore - 2, balance(admin, first.id()));
		assertEquals(secondBefore - 1, balance(admin, second.id()));
		assertEquals(firstMovements + 1, stockService.listMovements(admin, first.id()).size());
		assertEquals(secondMovements + 1, stockService.listMovements(admin, second.id()).size());
		assertEquals("CONFIRMADO", onlineOrderService.findOwn(client, order.id()).status());
	}

	@Test
	void sendsAnInsufficientMultilineOrderToReviewWithoutAnyPartialDeduction() {
		SessionPrincipal admin = administrator("veterinaria-aurora", "AV_DEMO_AURORA_PASSWORD");
		SessionPrincipal client = client("veterinaria-aurora", "AV_DEMO_CLIENT_AURORA_EMAIL", "AV_DEMO_CLIENT_AURORA_PASSWORD");
		String suffix = UUID.randomUUID().toString().substring(0, 8);
		CatalogProduct available = product(admin, suffix + "A", new BigDecimal("10.00"), 2);
		CatalogProduct exhaustedAfterRequest = product(admin, suffix + "B", new BigDecimal("8.00"), 1);
		Order order = onlineOrderService.create(client, draft(available, 1, exhaustedAfterRequest, 1));
		stockService.adjust(admin, exhaustedAfterRequest.id(), new StockAdjustmentDraft(-1, "Consumo sintético antes de confirmar", UUID.randomUUID()));
		int availableBefore = balance(admin, available.id());
		int exhaustedBefore = balance(admin, exhaustedAfterRequest.id());
		int availableMovements = stockService.listMovements(admin, available.id()).size();
		int exhaustedMovements = stockService.listMovements(admin, exhaustedAfterRequest.id()).size();

		StaffOrder reviewed = onlineOrderService.confirm(admin, order.id());
		assertEquals("EN_REVISION", reviewed.status());
		assertNotNull(reviewed.reviewReason());
		assertFalse(reviewed.reviewReason().isBlank());
		assertEquals(availableBefore, balance(admin, available.id()));
		assertEquals(exhaustedBefore, balance(admin, exhaustedAfterRequest.id()));
		assertEquals(availableMovements, stockService.listMovements(admin, available.id()).size());
		assertEquals(exhaustedMovements, stockService.listMovements(admin, exhaustedAfterRequest.id()).size());
		assertEquals(1, reviewed.history().stream().filter(event -> "EN_REVISION".equals(event.status())).count());
	}

	@Test
	void permitsOnlyCommercialStaffFromTheCurrentActiveTenant() {
		SessionPrincipal admin = administrator("veterinaria-aurora", "AV_DEMO_AURORA_PASSWORD");
		SessionPrincipal cashier = staff("veterinaria-aurora", required("AV_DEMO_CASHIER_AURORA_EMAIL"), "AV_DEMO_CASHIER_AURORA_PASSWORD");
		SessionPrincipal veterinarian = staff("veterinaria-aurora", required("AV_DEMO_VETERINARIO_AURORA_EMAIL"), "AV_DEMO_VETERINARIO_AURORA_PASSWORD");
		SessionPrincipal client = client("veterinaria-aurora", "AV_DEMO_CLIENT_AURORA_EMAIL", "AV_DEMO_CLIENT_AURORA_PASSWORD");
		SessionPrincipal bosqueAdmin = administrator("veterinaria-bosque", "AV_DEMO_BOSQUE_PASSWORD");
		SessionPrincipal saas = new SessionPrincipal(identityAccessService.authenticateSaas(required("AV_DEMO_SAAS_EMAIL"), required("AV_DEMO_SAAS_PASSWORD")).context());
		CatalogProduct product = product(admin, UUID.randomUUID().toString().substring(0, 8), new BigDecimal("11.00"), 2);
		Order order = onlineOrderService.create(client, draft(product, 1, null, 0));

		assertNotNull(onlineOrderService.listManagement(admin));
		assertNotNull(onlineOrderService.listManagement(cashier));
		assertForbidden(() -> onlineOrderService.listManagement(client), "B05_COMMERCIAL_OPERATOR_REQUIRED");
		assertForbidden(() -> onlineOrderService.listManagement(veterinarian), "B05_COMMERCIAL_OPERATOR_REQUIRED");
		assertForbidden(() -> onlineOrderService.listManagement(saas), "B05_COMMERCIAL_OPERATOR_REQUIRED");
		ApiException foreign = assertThrows(ApiException.class, () -> onlineOrderService.findManagement(bosqueAdmin, order.id()));
		assertEquals(HttpStatus.NOT_FOUND, foreign.status());
		assertEquals("B05_ORDER_NOT_FOUND", foreign.code());
		TenantSession current = admin.session();
		SessionPrincipal suspended = new SessionPrincipal(new TenantSession(current.sessionId(), current.accountId(), current.tenantId(),
				current.tenantSlug(), current.tenantCommercialName(), "SUSPENDIDO", current.accountType(), current.roles(),
				current.sessionTokenHash(), Instant.now().plusSeconds(300)));
		assertForbidden(() -> onlineOrderService.confirm(suspended, order.id()), "TENANT_NOT_ACTIVE");
	}

	private CatalogProduct product(SessionPrincipal admin, String suffix, BigDecimal price, int quantity) {
		CatalogProduct product = catalogService.createProduct(admin, new CatalogProductDraft(null, "B05-C1-" + suffix,
				"Pedido C1 " + suffix, null, price, "ACTIVO_PUBLICADO", true, null));
		stockService.adjust(admin, product.id(), new StockAdjustmentDraft(quantity, "Saldo sintético B05-C1", UUID.randomUUID()));
		return product;
	}

	private Draft draft(CatalogProduct first, int firstQuantity, CatalogProduct second, int secondQuantity) {
		List<LineDraft> lines = new java.util.ArrayList<>();
		lines.add(new LineDraft(first.id(), firstQuantity, first.salePricePen()));
		if (second != null) lines.add(new LineDraft(second.id(), secondQuantity, second.salePricePen()));
		return new Draft(UUID.randomUUID(), DeliveryMode.RECOJO, "999 000 111", null, null, lines);
	}

	private int balance(SessionPrincipal admin, UUID productId) {
		return stockService.listBalances(admin).stream().filter(item -> item.productId().equals(productId)).findFirst().orElseThrow().availableQuantity();
	}

	private SessionPrincipal administrator(String tenantSlug, String passwordVariable) { return staff(tenantSlug, "administracion@demo.invalid", passwordVariable); }
	private SessionPrincipal staff(String tenantSlug, String email, String passwordVariable) { return new SessionPrincipal(identityAccessService.authenticateStaff(tenantSlug, email, required(passwordVariable)).context()); }
	private SessionPrincipal client(String tenantSlug, String emailVariable, String passwordVariable) { return new SessionPrincipal(identityAccessService.authenticateClient(tenantSlug, required(emailVariable), required(passwordVariable)).context()); }
	private String required(String name) { String value = System.getenv(name); Assertions.assertNotNull(value, "La prueba requiere la variable local " + name + "."); Assertions.assertFalse(value.isBlank(), "La variable local " + name + " no puede estar vacía."); return value; }
	private void assertForbidden(org.junit.jupiter.api.function.Executable executable, String code) { ApiException exception = assertThrows(ApiException.class, executable); assertEquals(HttpStatus.FORBIDDEN, exception.status()); assertEquals(code, exception.code()); }
}
