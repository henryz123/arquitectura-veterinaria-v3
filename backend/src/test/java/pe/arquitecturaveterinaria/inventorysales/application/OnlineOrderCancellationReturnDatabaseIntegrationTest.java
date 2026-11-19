package pe.arquitecturaveterinaria.inventorysales.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
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
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.CancellationDecision;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.CancellationDraft;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.CancellationStatus;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.DeliveryCompletionDraft;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.DeliveryMode;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.Draft;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.LineDraft;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.ManualPaymentDecision;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.ManualPaymentDraft;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.ManualPaymentStatus;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.OnlineReturnStatus;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.Order;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.ReturnDecision;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.ReturnDraft;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.ReturnInspectionLineDraft;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.ReturnLineDraft;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.ReturnReceiptLineDraft;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.StaffOrder;
import pe.arquitecturaveterinaria.notificationsreporting.application.InternalNotificationService;
import pe.arquitecturaveterinaria.shared.api.ApiException;

/** B05-C4 keeps cancellation, physical return inspection and stock restitution tenant-local and idempotent. */
@SpringBootTest
@Transactional(transactionManager = "jdbcTransactionManager")
class OnlineOrderCancellationReturnDatabaseIntegrationTest {

	@Autowired private CatalogService catalogService;
	@Autowired private StockService stockService;
	@Autowired private OnlineOrderService onlineOrderService;
	@Autowired private IdentityAccessService identityAccessService;
	@Autowired private InternalNotificationService notifications;

	@Test
	void clientCancelsPendingOrderWithoutAnyInventoryMovementAndReplayIsSafe() {
		SessionPrincipal admin = administrator("veterinaria-aurora", "AV_DEMO_AURORA_PASSWORD");
		SessionPrincipal client = client("veterinaria-aurora", "AV_DEMO_CLIENT_AURORA_EMAIL", "AV_DEMO_CLIENT_AURORA_PASSWORD");
		CatalogProduct product = product(admin, "pending", new BigDecimal("15.00"), 3);
		Order order = onlineOrderService.create(client, draft(product, 1));
		int balanceBefore = balance(admin, product.id());
		int movementsBefore = stockService.listMovements(admin, product.id()).size();

		CancellationDraft command = new CancellationDraft(UUID.randomUUID(), "El cliente modificó su pedido sintético.");
		Order cancelled = onlineOrderService.cancelPending(client, order.id(), command);
		assertEquals("CANCELADO", cancelled.status());
		assertNotNull(cancelled.cancellation());
		assertEquals(CancellationStatus.CANCELADA, cancelled.cancellation().status());
		assertFalse(cancelled.cancellation().stockRestocked());
		assertEquals(balanceBefore, balance(admin, product.id()));
		assertEquals(movementsBefore, stockService.listMovements(admin, product.id()).size());

		Order replay = onlineOrderService.cancelPending(client, order.id(), command);
		assertEquals("CANCELADO", replay.status());
		assertEquals(balanceBefore, balance(admin, product.id()));
		assertEquals(movementsBefore, stockService.listMovements(admin, product.id()).size());
	}

	@Test
	void approvedPreDispatchCancellationRestocksOnceAndLeavesValidatedPaymentForManualResolution() {
		SessionPrincipal admin = administrator("veterinaria-aurora", "AV_DEMO_AURORA_PASSWORD");
		SessionPrincipal client = client("veterinaria-aurora", "AV_DEMO_CLIENT_AURORA_EMAIL", "AV_DEMO_CLIENT_AURORA_PASSWORD");
		CatalogProduct product = product(admin, "cancellation", new BigDecimal("18.00"), 2);
		Order order = confirmedOrder(admin, client, product);
		int balanceAfterConfirmation = balance(admin, product.id());
		int movementsAfterConfirmation = stockService.listMovements(admin, product.id()).size();

		StaffOrder declared = onlineOrderService.recordManualPayment(admin, order.id(),
				new ManualPaymentDraft(UUID.randomUUID(), "EFECTIVO", order.totalPen(), "Pago sintético"));
		onlineOrderService.decideManualPayment(admin, order.id(), declared.payments().getFirst().id(),
				new ManualPaymentDecision(UUID.randomUUID(), ManualPaymentStatus.VALIDADO, null));
		Order requested = onlineOrderService.requestCancellation(client, order.id(),
				new CancellationDraft(UUID.randomUUID(), "Ya no requiere el recojo sintético."));
		assertEquals("CANCELACION_SOLICITADA", requested.status());
		assertNotNull(requested.cancellation());
		assertEquals(CancellationStatus.SOLICITADA, requested.cancellation().status());
		assertEquals(true, requested.cancellation().financialResolutionPending());
		assertEquals(balanceAfterConfirmation, balance(admin, product.id()));

		CancellationDecision decision = new CancellationDecision(UUID.randomUUID(), CancellationStatus.CANCELADA, "Producto aún disponible en sede.");
		StaffOrder cancelled = onlineOrderService.decideCancellation(admin, requested.cancellation().id(), decision);
		assertEquals("CANCELADO", cancelled.status());
		assertEquals("VALIDADO", cancelled.paymentStatus());
		assertNotNull(cancelled.cancellation());
		assertEquals(CancellationStatus.CANCELADA, cancelled.cancellation().status());
		assertEquals(true, cancelled.cancellation().stockRestocked());
		assertEquals(2, balance(admin, product.id()));
		assertEquals(movementsAfterConfirmation + 1, stockService.listMovements(admin, product.id()).size());
		assertTrue(notifications.listOwn(client).stream().anyMatch(item -> item.sourceResourceId().equals(order.id())
				&& "ONLINE_ORDER_CANCELLATION_RESOLVED".equals(item.notificationCode())));

		StaffOrder replay = onlineOrderService.decideCancellation(admin, requested.cancellation().id(), decision);
		assertEquals("CANCELADO", replay.status());
		assertEquals(2, balance(admin, product.id()));
		assertEquals(movementsAfterConfirmation + 1, stockService.listMovements(admin, product.id()).size());
	}

	@Test
	void deliveredOrderKeepsItsMainStateAndRestocksOnlyThePhysicallyFitReturnedQuantityOnce() {
		SessionPrincipal admin = administrator("veterinaria-aurora", "AV_DEMO_AURORA_PASSWORD");
		SessionPrincipal client = client("veterinaria-aurora", "AV_DEMO_CLIENT_AURORA_EMAIL", "AV_DEMO_CLIENT_AURORA_PASSWORD");
		CatalogProduct product = product(admin, "return", new BigDecimal("11.00"), 3);
		Order order = onlineOrderService.create(client, draft(product, 2));
		assertEquals("CONFIRMADO", onlineOrderService.confirm(admin, order.id()).status());
		onlineOrderService.startPreparation(admin, order.id(), UUID.randomUUID());
		onlineOrderService.markReadyForPickup(admin, order.id(), UUID.randomUUID());
		assertEquals("ENTREGADO", onlineOrderService.completeDelivery(admin, order.id(),
				new DeliveryCompletionDraft(UUID.randomUUID(), "Cliente Aurora sintético", "Constancia de recojo.")).status());
		int balanceAfterDelivery = balance(admin, product.id());
		int movementsAfterDelivery = stockService.listMovements(admin, product.id()).size();

		Order requested = onlineOrderService.requestReturn(client, order.id(), new ReturnDraft(UUID.randomUUID(), "Una unidad sintética no se utilizó.",
				List.of(new ReturnLineDraft(product.id(), 1))));
		assertEquals("ENTREGADO", requested.status());
		assertEquals(1, requested.returns().size());
		UUID returnId = requested.returns().getFirst().id();
		StaffOrder authorized = onlineOrderService.decideReturn(admin, returnId,
				new ReturnDecision(UUID.randomUUID(), OnlineReturnStatus.AUTORIZADA, null));
		assertEquals("ENTREGADO", authorized.status());
		assertTrue(notifications.listOwn(client).stream().anyMatch(item -> item.sourceResourceId().equals(order.id())
				&& "ONLINE_ORDER_RETURN_RESOLVED".equals(item.notificationCode())));
		UUID returnLineId = authorized.returns().getFirst().lines().getFirst().id();
		onlineOrderService.receiveReturn(admin, returnId, List.of(new ReturnReceiptLineDraft(returnLineId, 1)));
		StaffOrder inspected = onlineOrderService.inspectReturn(admin, returnId, List.of(new ReturnInspectionLineDraft(returnLineId, 1)));
		assertEquals("ENTREGADO", inspected.status());
		assertEquals(OnlineReturnStatus.INSPECCIONADA_APTA, inspected.returns().getFirst().status());
		assertEquals(balanceAfterDelivery + 1, balance(admin, product.id()));
		assertEquals(movementsAfterDelivery + 1, stockService.listMovements(admin, product.id()).size());

		StaffOrder inspectionReplay = onlineOrderService.inspectReturn(admin, returnId, List.of(new ReturnInspectionLineDraft(returnLineId, 1)));
		assertEquals(OnlineReturnStatus.INSPECCIONADA_APTA, inspectionReplay.returns().getFirst().status());
		assertEquals(balanceAfterDelivery + 1, balance(admin, product.id()));
		assertEquals(movementsAfterDelivery + 1, stockService.listMovements(admin, product.id()).size());
		assertEquals(OnlineReturnStatus.CERRADA, onlineOrderService.closeReturn(admin, returnId).returns().getFirst().status());

		ApiException overReturn = assertThrows(ApiException.class, () -> onlineOrderService.requestReturn(client, order.id(),
				new ReturnDraft(UUID.randomUUID(), "Cantidad que excede el saldo devuelto.", List.of(new ReturnLineDraft(product.id(), 2)))));
		assertEquals("B05_RETURN_QUANTITY_EXCEEDED", overReturn.code());
	}

	@Test
	void blocksForeignTenantAndClientFromCommercialCancellationAndReturnDecisions() {
		SessionPrincipal admin = administrator("veterinaria-aurora", "AV_DEMO_AURORA_PASSWORD");
		SessionPrincipal client = client("veterinaria-aurora", "AV_DEMO_CLIENT_AURORA_EMAIL", "AV_DEMO_CLIENT_AURORA_PASSWORD");
		SessionPrincipal bosqueAdmin = administrator("veterinaria-bosque", "AV_DEMO_BOSQUE_PASSWORD");
		CatalogProduct product = product(admin, "security", new BigDecimal("12.00"), 2);
		Order order = onlineOrderService.create(client, draft(product, 1));

		assertForbidden(() -> onlineOrderService.cancelPreDispatch(client, order.id(), new CancellationDraft(UUID.randomUUID(), "No autorizado por cliente.")));
		ApiException foreign = assertThrows(ApiException.class, () -> onlineOrderService.cancelPreDispatch(bosqueAdmin, order.id(),
				new CancellationDraft(UUID.randomUUID(), "Otro tenant no puede cancelar.")));
		assertEquals(HttpStatus.NOT_FOUND, foreign.status());
	}

	private Order confirmedOrder(SessionPrincipal admin, SessionPrincipal client, CatalogProduct product) {
		Order order = onlineOrderService.create(client, draft(product, 1));
		assertEquals("CONFIRMADO", onlineOrderService.confirm(admin, order.id()).status());
		return order;
	}

	private Draft draft(CatalogProduct product, int quantity) {
		return new Draft(UUID.randomUUID(), DeliveryMode.RECOJO, "999 555 001", null, null,
				List.of(new LineDraft(product.id(), quantity, product.salePricePen())));
	}

	private CatalogProduct product(SessionPrincipal admin, String label, BigDecimal price, int quantity) {
		String suffix = UUID.randomUUID().toString().substring(0, 8);
		CatalogProduct product = catalogService.createProduct(admin, new CatalogProductDraft(null, "B05-C4-" + label + '-' + suffix,
				"Pedido C4 " + label + ' ' + suffix, null, price, "ACTIVO_PUBLICADO", true, null));
		stockService.adjust(admin, product.id(), new StockAdjustmentDraft(quantity, "Saldo sintético B05-C4", UUID.randomUUID()));
		return product;
	}

	private int balance(SessionPrincipal admin, UUID productId) {
		return stockService.listBalances(admin).stream().filter(item -> item.productId().equals(productId)).findFirst().orElseThrow().availableQuantity();
	}

	private SessionPrincipal administrator(String tenant, String passwordVariable) { return staff(tenant, "administracion@demo.invalid", passwordVariable); }
	private SessionPrincipal staff(String tenant, String email, String passwordVariable) { return new SessionPrincipal(identityAccessService.authenticateStaff(tenant, email, required(passwordVariable)).context()); }
	private SessionPrincipal client(String tenant, String emailVariable, String passwordVariable) { return new SessionPrincipal(identityAccessService.authenticateClient(tenant, required(emailVariable), required(passwordVariable)).context()); }
	private String required(String name) { String value = System.getenv(name); Assertions.assertNotNull(value, "La prueba requiere la variable local " + name + '.'); Assertions.assertFalse(value.isBlank(), "La variable local no puede estar vacía."); return value; }
	private void assertForbidden(org.junit.jupiter.api.function.Executable action) { assertEquals(HttpStatus.FORBIDDEN, assertThrows(ApiException.class, action).status()); }
}
