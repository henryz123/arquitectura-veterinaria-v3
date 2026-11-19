package pe.arquitecturaveterinaria.inventorysales.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.DeliveryMode;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.Draft;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.LineDraft;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.Order;
import pe.arquitecturaveterinaria.shared.api.ApiException;

/**
 * Integration evidence for B05-B. The class transaction rolls back all
 * synthetic catalog, configuration and order data; pending orders never call
 * the V024 stock authority.
 */
@SpringBootTest
@Transactional(transactionManager = "jdbcTransactionManager")
class OnlineOrderDatabaseIntegrationTest {

	@Autowired private CatalogService catalogService;
	@Autowired private StockService stockService;
	@Autowired private OnlineOrderService onlineOrderService;
	@Autowired private IdentityAccessService identityAccessService;

	@Test
	void persistsTenantLocalPendingPickupAndDeliveryOrdersWithoutMovingStock() {
		SessionPrincipal administrator = administrator("veterinaria-aurora", "AV_DEMO_AURORA_PASSWORD");
		SessionPrincipal auroraClient = client("veterinaria-aurora", "AV_DEMO_CLIENT_AURORA_EMAIL", "AV_DEMO_CLIENT_AURORA_PASSWORD");
		SessionPrincipal bosqueClient = client("veterinaria-bosque", "AV_DEMO_CLIENT_BOSQUE_EMAIL", "AV_DEMO_CLIENT_BOSQUE_PASSWORD");
		String suffix = UUID.randomUUID().toString().substring(0, 8);
		CatalogProduct product = catalogService.createProduct(administrator,
				new CatalogProductDraft(null, "B05-B-" + suffix, "Pedido sintético " + suffix, null,
						new BigDecimal("14.50"), "ACTIVO_PUBLICADO", true, null));
		stockService.adjust(administrator, product.id(), new StockAdjustmentDraft(3, "Saldo B05-B sintético", UUID.randomUUID()));
		onlineOrderService.updateDeliveryConfiguration(administrator, "Recojo B05-B", true, new BigDecimal("9.50"), "Cobertura sintética");

		int stockBefore = stockService.listBalances(administrator).stream()
				.filter(item -> item.productId().equals(product.id())).findFirst().orElseThrow().availableQuantity();
		int movementsBefore = stockService.listMovements(administrator, product.id()).size();
		UUID pickupOperation = UUID.randomUUID();
		Draft pickup = new Draft(pickupOperation, DeliveryMode.RECOJO, "999 000 111", null, null,
				List.of(new LineDraft(product.id(), 2, new BigDecimal("14.50"))));
		Order pickupOrder = onlineOrderService.create(auroraClient, pickup);

		assertEquals("PENDIENTE", pickupOrder.status());
		assertEquals(DeliveryMode.RECOJO, pickupOrder.deliveryMode());
		assertEquals(new BigDecimal("29.00"), pickupOrder.subtotalPen());
		assertEquals(BigDecimal.ZERO.setScale(2), pickupOrder.deliveryFeePen());
		assertEquals(new BigDecimal("29.00"), pickupOrder.totalPen());
		assertEquals(1, pickupOrder.lines().size());
		assertEquals(pickupOrder.id(), onlineOrderService.create(auroraClient, pickup).id(), "El reintento debe devolver el mismo pedido.");
		assertEquals(pickupOrder.id(), onlineOrderService.findOwn(auroraClient, pickupOrder.id()).id());
		assertEquals(1, onlineOrderService.listOwn(auroraClient).stream().filter(order -> order.id().equals(pickupOrder.id())).count());
		ApiException replayConflict = assertThrows(ApiException.class, () -> onlineOrderService.create(auroraClient,
				new Draft(pickupOperation, DeliveryMode.RECOJO, "999 000 111", null, null,
						List.of(new LineDraft(product.id(), 2, new BigDecimal("14.51"))))));
		assertEquals(HttpStatus.CONFLICT, replayConflict.status());
		assertEquals("B05_ORDER_REPLAY_CONFLICT", replayConflict.code());

		Order deliveryOrder = onlineOrderService.create(auroraClient,
				new Draft(UUID.randomUUID(), DeliveryMode.DELIVERY, "999 000 222", "Dirección sintética 123", new BigDecimal("9.50"),
						List.of(new LineDraft(product.id(), 1, new BigDecimal("14.50")))));
		assertEquals("PENDIENTE", deliveryOrder.status());
		assertEquals(new BigDecimal("9.50"), deliveryOrder.deliveryFeePen());
		assertEquals(new BigDecimal("24.00"), deliveryOrder.totalPen());
		assertEquals(stockBefore, stockService.listBalances(administrator).stream()
				.filter(item -> item.productId().equals(product.id())).findFirst().orElseThrow().availableQuantity());
		assertEquals(movementsBefore, stockService.listMovements(administrator, product.id()).size());

		ApiException crossTenant = assertThrows(ApiException.class, () -> onlineOrderService.findOwn(bosqueClient, pickupOrder.id()));
		assertEquals(HttpStatus.NOT_FOUND, crossTenant.status());
		assertEquals("B05_ORDER_NOT_FOUND", crossTenant.code());

		ApiException invalidQuantity = assertThrows(ApiException.class, () -> onlineOrderService.create(auroraClient,
				new Draft(UUID.randomUUID(), DeliveryMode.RECOJO, "999 000 333", null, null,
						List.of(new LineDraft(product.id(), 0, new BigDecimal("14.50"))))));
		assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, invalidQuantity.status());
		assertEquals("B05_ORDER_VALIDATION", invalidQuantity.code());

		CatalogProduct noStock = catalogService.createProduct(administrator,
				new CatalogProductDraft(null, "B05-ZERO-" + suffix, "Producto sin saldo " + suffix, null,
						new BigDecimal("12.00"), "ACTIVO_PUBLICADO", true, null));
		ApiException noStockRejected = assertThrows(ApiException.class, () -> onlineOrderService.create(auroraClient,
				new Draft(UUID.randomUUID(), DeliveryMode.RECOJO, "999 000 333", null, null,
						List.of(new LineDraft(noStock.id(), 1, new BigDecimal("12.00"))))));
		assertEquals(HttpStatus.CONFLICT, noStockRejected.status());
		assertEquals("B05_PRODUCT_UNAVAILABLE", noStockRejected.code());

		catalogService.updateProduct(administrator, product.id(), new CatalogProductDraft(null, product.sku(), product.title(), null,
				new BigDecimal("14.50"), "INACTIVO", true, null));
		ApiException unavailable = assertThrows(ApiException.class, () -> onlineOrderService.create(auroraClient,
				new Draft(UUID.randomUUID(), DeliveryMode.RECOJO, "999 000 444", null, null,
						List.of(new LineDraft(product.id(), 1, new BigDecimal("14.50"))))));
		assertEquals(HttpStatus.CONFLICT, unavailable.status());
		assertEquals("B05_PRODUCT_UNAVAILABLE", unavailable.code());
	}

	private SessionPrincipal administrator(String tenantSlug, String passwordVariable) {
		String password = required(passwordVariable);
		return new SessionPrincipal(identityAccessService.authenticateStaff(tenantSlug, "administracion@demo.invalid", password).context());
	}

	private SessionPrincipal client(String tenantSlug, String emailVariable, String passwordVariable) {
		return new SessionPrincipal(identityAccessService.authenticateClient(tenantSlug, required(emailVariable), required(passwordVariable)).context());
	}

	private String required(String name) {
		String value = System.getenv(name);
		Assertions.assertNotNull(value, "La prueba requiere la variable local " + name + ".");
		Assertions.assertFalse(value.isBlank(), "La variable local " + name + " no puede estar vacía.");
		return value;
	}
}
