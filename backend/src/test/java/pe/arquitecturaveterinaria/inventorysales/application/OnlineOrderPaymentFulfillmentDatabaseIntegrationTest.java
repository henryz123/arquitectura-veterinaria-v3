package pe.arquitecturaveterinaria.inventorysales.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.DeliveryCompletionDraft;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.DeliveryMode;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.Draft;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.LineDraft;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.ManualPaymentDecision;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.ManualPaymentDraft;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.ManualPaymentStatus;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.Order;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.StaffOrder;
import pe.arquitecturaveterinaria.notificationsreporting.application.InternalNotificationService;
import pe.arquitecturaveterinaria.shared.api.ApiException;

/** B05-C3 keeps manual payment and physical fulfillment auditable and independent from stock. */
@SpringBootTest
@Transactional(transactionManager = "jdbcTransactionManager")
class OnlineOrderPaymentFulfillmentDatabaseIntegrationTest {

	@Autowired private CatalogService catalogService;
	@Autowired private StockService stockService;
	@Autowired private OnlineOrderService onlineOrderService;
	@Autowired private IdentityAccessService identityAccessService;
	@Autowired private InternalNotificationService notifications;

	@Test
	void recordsAndValidatesOneManualPaymentWithoutAnotherStockMovement() {
		SessionPrincipal admin = administrator("veterinaria-aurora", "AV_DEMO_AURORA_PASSWORD");
		SessionPrincipal client = client("veterinaria-aurora", "AV_DEMO_CLIENT_AURORA_EMAIL", "AV_DEMO_CLIENT_AURORA_PASSWORD");
		CatalogProduct product = product(admin, "payment", new BigDecimal("16.50"), 3);
		Order order = confirmedOrder(admin, client, product, DeliveryMode.RECOJO);
		int balanceAfterConfirmation = balance(admin, product.id());
		int movementsAfterConfirmation = stockService.listMovements(admin, product.id()).size();

		UUID declarationOperation = UUID.randomUUID();
		StaffOrder declared = onlineOrderService.recordManualPayment(admin, order.id(),
				new ManualPaymentDraft(declarationOperation, "EFECTIVO", order.totalPen(), null));
		assertEquals("DECLARADO", declared.paymentStatus());
		assertEquals(1, declared.payments().size());
		assertEquals(order.totalPen(), declared.payments().getFirst().declaredAmountPen());
		assertEquals(balanceAfterConfirmation, balance(admin, product.id()));
		assertEquals(movementsAfterConfirmation, stockService.listMovements(admin, product.id()).size());

		UUID validationOperation = UUID.randomUUID();
		StaffOrder validated = onlineOrderService.decideManualPayment(admin, order.id(), declared.payments().getFirst().id(),
				new ManualPaymentDecision(validationOperation, ManualPaymentStatus.VALIDADO, null));
		assertEquals("VALIDADO", validated.paymentStatus());
		assertEquals(ManualPaymentStatus.VALIDADO, validated.payments().getFirst().status());
		assertEquals("VALIDADO", onlineOrderService.findOwn(client, order.id()).paymentStatus());
		assertEquals(balanceAfterConfirmation, balance(admin, product.id()));
		assertEquals(movementsAfterConfirmation, stockService.listMovements(admin, product.id()).size());

		StaffOrder replay = onlineOrderService.decideManualPayment(admin, order.id(), declared.payments().getFirst().id(),
				new ManualPaymentDecision(validationOperation, ManualPaymentStatus.VALIDADO, null));
		assertEquals("VALIDADO", replay.paymentStatus());
		assertEquals(1, replay.payments().size());
		assertEquals(movementsAfterConfirmation, stockService.listMovements(admin, product.id()).size());
	}

	@Test
	void pickupFlowRequiresTheRightSequenceAndPersistsItsPhysicalReceipt() {
		SessionPrincipal admin = administrator("veterinaria-aurora", "AV_DEMO_AURORA_PASSWORD");
		SessionPrincipal client = client("veterinaria-aurora", "AV_DEMO_CLIENT_AURORA_EMAIL", "AV_DEMO_CLIENT_AURORA_PASSWORD");
		CatalogProduct product = product(admin, "pickup", new BigDecimal("9.00"), 2);
		Order order = confirmedOrder(admin, client, product, DeliveryMode.RECOJO);
		int balanceAfterConfirmation = balance(admin, product.id());
		int movementsAfterConfirmation = stockService.listMovements(admin, product.id()).size();

		assertEquals("EN_PREPARACION", onlineOrderService.startPreparation(admin, order.id(), UUID.randomUUID()).status());
		assertEquals("LISTO_PARA_RECOJO", onlineOrderService.markReadyForPickup(admin, order.id(), UUID.randomUUID()).status());
		StaffOrder delivered = onlineOrderService.completeDelivery(admin, order.id(),
				new DeliveryCompletionDraft(UUID.randomUUID(), "Cliente sintético Aurora", "Retiro registrado en sede."));
		assertEquals("ENTREGADO", delivered.status());
		assertNotNull(delivered.deliveryRecord());
		assertEquals("Cliente sintético Aurora", delivered.deliveryRecord().receiver());
		assertEquals("ENTREGADO", onlineOrderService.findOwn(client, order.id()).status());
		assertEquals(balanceAfterConfirmation, balance(admin, product.id()));
		assertEquals(movementsAfterConfirmation, stockService.listMovements(admin, product.id()).size());
		List<String> notificationCodes = notifications.listOwn(client).stream().filter(item -> item.sourceResourceId().equals(order.id()))
				.map(item -> item.notificationCode()).toList();
		assertTrue(notificationCodes.containsAll(List.of("ONLINE_ORDER_CREATED", "ONLINE_ORDER_CONFIRMED",
				"ONLINE_ORDER_READY_FOR_PICKUP", "ONLINE_ORDER_DELIVERED")));

		StaffOrder duplicate = onlineOrderService.completeDelivery(admin, order.id(),
				new DeliveryCompletionDraft(UUID.randomUUID(), "Cliente sintético Aurora", "Reintento seguro."));
		assertEquals("ENTREGADO", duplicate.status());
		assertEquals(1, duplicate.history().stream().filter(event -> "ENTREGADO".equals(event.status())).count());
	}

	@Test
	void deliveryFlowCannotUsePickupStateAndKeepsTheAgreedDeliveryFee() {
		SessionPrincipal admin = administrator("veterinaria-aurora", "AV_DEMO_AURORA_PASSWORD");
		SessionPrincipal client = client("veterinaria-aurora", "AV_DEMO_CLIENT_AURORA_EMAIL", "AV_DEMO_CLIENT_AURORA_PASSWORD");
		onlineOrderService.updateDeliveryConfiguration(admin, "Sede Aurora", true, new BigDecimal("9.50"), "Zona sintética Aurora");
		CatalogProduct product = product(admin, "delivery", new BigDecimal("12.00"), 2);
		Order order = onlineOrderService.create(client, new Draft(UUID.randomUUID(), DeliveryMode.DELIVERY, "999 101 101",
				"Calle sintética 101", new BigDecimal("9.50"), List.of(new LineDraft(product.id(), 1, product.salePricePen()))));
		StaffOrder confirmed = onlineOrderService.confirm(admin, order.id());
		assertEquals(new BigDecimal("21.50"), confirmed.totalPen());
		assertEquals("EN_PREPARACION", onlineOrderService.startPreparation(admin, order.id(), UUID.randomUUID()).status());
		ApiException invalidPickupState = assertThrows(ApiException.class,
				() -> onlineOrderService.markReadyForPickup(admin, order.id(), UUID.randomUUID()));
		assertEquals("B05_FULFILLMENT_MODE", invalidPickupState.code());
		assertEquals("EN_REPARTO", onlineOrderService.markDispatched(admin, order.id(), UUID.randomUUID()).status());
		StaffOrder delivered = onlineOrderService.completeDelivery(admin, order.id(),
				new DeliveryCompletionDraft(UUID.randomUUID(), "Receptor sintético", "Entrega manual registrada."));
		assertEquals("ENTREGADO", delivered.status());
		assertEquals(DeliveryMode.DELIVERY, delivered.deliveryRecord().deliveryMode());
		assertEquals(new BigDecimal("9.50"), delivered.deliveryRecord().deliveryFeePen());
	}

	@Test
	void rejectsWrongActorWrongTenantAndInconsistentPaymentAmount() {
		SessionPrincipal admin = administrator("veterinaria-aurora", "AV_DEMO_AURORA_PASSWORD");
		SessionPrincipal client = client("veterinaria-aurora", "AV_DEMO_CLIENT_AURORA_EMAIL", "AV_DEMO_CLIENT_AURORA_PASSWORD");
		SessionPrincipal bosqueAdmin = administrator("veterinaria-bosque", "AV_DEMO_BOSQUE_PASSWORD");
		CatalogProduct product = product(admin, "security", new BigDecimal("13.00"), 2);
		Order order = confirmedOrder(admin, client, product, DeliveryMode.RECOJO);

		assertForbidden(() -> onlineOrderService.recordManualPayment(client, order.id(),
				new ManualPaymentDraft(UUID.randomUUID(), "EFECTIVO", order.totalPen(), null)));
		ApiException foreign = assertThrows(ApiException.class, () -> onlineOrderService.recordManualPayment(bosqueAdmin, order.id(),
				new ManualPaymentDraft(UUID.randomUUID(), "EFECTIVO", order.totalPen(), null)));
		assertEquals(HttpStatus.NOT_FOUND, foreign.status());
		ApiException inconsistent = assertThrows(ApiException.class, () -> onlineOrderService.recordManualPayment(admin, order.id(),
				new ManualPaymentDraft(UUID.randomUUID(), "TRANSFERENCIA", new BigDecimal("0.01"), "Referencia sintética")));
		assertEquals("B05_ORDER_VALIDATION", inconsistent.code());
	}

	private Order confirmedOrder(SessionPrincipal admin, SessionPrincipal client, CatalogProduct product, DeliveryMode mode) {
		Order order = onlineOrderService.create(client, new Draft(UUID.randomUUID(), mode, "999 100 100",
				mode == DeliveryMode.DELIVERY ? "Calle sintética 100" : null,
				mode == DeliveryMode.DELIVERY ? new BigDecimal("9.50") : null,
				List.of(new LineDraft(product.id(), 1, product.salePricePen()))));
		assertEquals("CONFIRMADO", onlineOrderService.confirm(admin, order.id()).status());
		return order;
	}

	private CatalogProduct product(SessionPrincipal admin, String label, BigDecimal price, int quantity) {
		String suffix = UUID.randomUUID().toString().substring(0, 8);
		CatalogProduct product = catalogService.createProduct(admin, new CatalogProductDraft(null, "B05-C3-" + label + '-' + suffix,
				"Pedido C3 " + label + ' ' + suffix, null, price, "ACTIVO_PUBLICADO", true, null));
		stockService.adjust(admin, product.id(), new StockAdjustmentDraft(quantity, "Saldo sintético B05-C3", UUID.randomUUID()));
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
