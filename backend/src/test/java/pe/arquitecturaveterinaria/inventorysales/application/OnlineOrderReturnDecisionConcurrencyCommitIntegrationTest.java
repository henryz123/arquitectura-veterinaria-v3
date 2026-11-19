package pe.arquitecturaveterinaria.inventorysales.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import pe.arquitecturaveterinaria.identityaccess.application.IdentityAccessService;
import pe.arquitecturaveterinaria.identityaccess.application.SessionPrincipal;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.DeliveryCompletionDraft;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.DeliveryMode;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.Draft;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.LineDraft;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.OnlineReturnStatus;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.ReturnDecision;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.ReturnDraft;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.ReturnLineDraft;
import pe.arquitecturaveterinaria.shared.api.ApiException;

/** Competing staff decisions produce one return outcome and no stock movement before physical inspection. */
@SpringBootTest
class OnlineOrderReturnDecisionConcurrencyCommitIntegrationTest {

	@Autowired private CatalogService catalogService;
	@Autowired private StockService stockService;
	@Autowired private OnlineOrderService onlineOrderService;
	@Autowired private IdentityAccessService identityAccessService;

	private UUID tenantId;
	private UUID productId;
	private UUID orderId;

	@Test
	void concurrentReturnDecisionsLeaveOneAuditableOutcomeAndNoPrematureRestock() throws Exception {
		SessionPrincipal admin = administrator();
		SessionPrincipal client = client();
		tenantId = admin.session().tenantId();
		String suffix = UUID.randomUUID().toString().substring(0, 8);
		CatalogProduct product = catalogService.createProduct(admin, new CatalogProductDraft(null, "B05-C4-RACE-" + suffix,
				"Carrera devolución C4 " + suffix, null, new BigDecimal("9.00"), "ACTIVO_PUBLICADO", true, null));
		productId = product.id();
		stockService.adjust(admin, productId, new StockAdjustmentDraft(2, "Saldo de carrera B05-C4", UUID.randomUUID()));
		var order = onlineOrderService.create(client, new Draft(UUID.randomUUID(), DeliveryMode.RECOJO, "999 112 233", null, null,
				List.of(new LineDraft(productId, 1, product.salePricePen()))));
		orderId = order.id();
		onlineOrderService.confirm(admin, orderId);
		onlineOrderService.startPreparation(admin, orderId, UUID.randomUUID());
		onlineOrderService.markReadyForPickup(admin, orderId, UUID.randomUUID());
		onlineOrderService.completeDelivery(admin, orderId, new DeliveryCompletionDraft(UUID.randomUUID(), "Cliente sintético", "Entrega C4."));
		int balanceAfterDelivery = stockService.listBalances(admin).stream().filter(item -> item.productId().equals(productId)).findFirst().orElseThrow().availableQuantity();
		int movementsAfterDelivery = stockService.listMovements(admin, productId).size();
		UUID returnId = onlineOrderService.requestReturn(client, orderId, new ReturnDraft(UUID.randomUUID(), "Prueba concurrente de devolución.",
				List.of(new ReturnLineDraft(productId, 1)))).returns().getFirst().id();

		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch release = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<Attempt> authorize = executor.submit(() -> decideWhenReleased(admin, returnId, OnlineReturnStatus.AUTORIZADA, ready, release));
			Future<Attempt> reject = executor.submit(() -> decideWhenReleased(admin, returnId, OnlineReturnStatus.RECHAZADA, ready, release));
			assertTrue(ready.await(5, TimeUnit.SECONDS));
			release.countDown();
			Attempt authorized = authorize.get(10, TimeUnit.SECONDS);
			Attempt rejected = reject.get(10, TimeUnit.SECONDS);
			assertEquals(1, (authorized.succeeded() ? 1 : 0) + (rejected.succeeded() ? 1 : 0), authorized + " / " + rejected);
			assertTrue((authorized.succeeded() && "B05_RETURN_STATE".equals(rejected.errorCode()))
					|| (rejected.succeeded() && "B05_RETURN_STATE".equals(authorized.errorCode())), authorized + " / " + rejected);
		} finally {
			executor.shutdownNow();
		}

		var result = onlineOrderService.findManagement(admin, orderId);
		assertEquals("ENTREGADO", result.status());
		assertEquals(1, result.returns().size());
		assertTrue(result.returns().getFirst().status() == OnlineReturnStatus.AUTORIZADA || result.returns().getFirst().status() == OnlineReturnStatus.RECHAZADA);
		assertEquals(balanceAfterDelivery, stockService.listBalances(admin).stream().filter(item -> item.productId().equals(productId)).findFirst().orElseThrow().availableQuantity());
		assertEquals(movementsAfterDelivery, stockService.listMovements(admin, productId).size());
	}

	@AfterEach
	void removeCommittedFixture() throws SQLException {
		if (tenantId == null || productId == null) return;
		try (Connection connection = DriverManager.getConnection(required("AV_DB_URL"), required("AV_MIGRATION_DB_USER"), required("AV_MIGRATION_DB_PASSWORD"))) {
			connection.setAutoCommit(false);
			try (PreparedStatement movements = connection.prepareStatement("delete from inventory_sales.stock_movement where tenant_id = ? and product_id = ?");
					PreparedStatement returnLines = connection.prepareStatement("delete from inventory_sales.online_order_return_line where tenant_id = ? and return_id in (select id from inventory_sales.online_order_return where tenant_id = ? and order_id = ?)");
					PreparedStatement returns = connection.prepareStatement("delete from inventory_sales.online_order_return where tenant_id = ? and order_id = ?");
					PreparedStatement delivery = connection.prepareStatement("delete from inventory_sales.online_order_delivery_record where tenant_id = ? and order_id = ?");
					PreparedStatement events = connection.prepareStatement("delete from inventory_sales.online_order_status_event where tenant_id = ? and order_id = ?");
					PreparedStatement orderLines = connection.prepareStatement("delete from inventory_sales.online_order_line where tenant_id = ? and order_id = ?");
					PreparedStatement orders = connection.prepareStatement("delete from inventory_sales.online_order where tenant_id = ? and id = ?");
					PreparedStatement balance = connection.prepareStatement("delete from inventory_sales.stock_balance where tenant_id = ? and product_id = ?");
					PreparedStatement product = connection.prepareStatement("delete from inventory_sales.product where tenant_id = ? and id = ?")) {
				movements.setObject(1, tenantId); movements.setObject(2, productId); movements.executeUpdate();
				returnLines.setObject(1, tenantId); returnLines.setObject(2, tenantId); returnLines.setObject(3, orderId); returnLines.executeUpdate();
				returns.setObject(1, tenantId); returns.setObject(2, orderId); returns.executeUpdate();
				delivery.setObject(1, tenantId); delivery.setObject(2, orderId); delivery.executeUpdate();
				events.setObject(1, tenantId); events.setObject(2, orderId); events.executeUpdate();
				orderLines.setObject(1, tenantId); orderLines.setObject(2, orderId); orderLines.executeUpdate();
				orders.setObject(1, tenantId); orders.setObject(2, orderId); orders.executeUpdate();
				balance.setObject(1, tenantId); balance.setObject(2, productId); balance.executeUpdate();
				product.setObject(1, tenantId); product.setObject(2, productId); product.executeUpdate();
				connection.commit();
			} catch (SQLException exception) { connection.rollback(); throw exception; }
		}
	}

	private Attempt decideWhenReleased(SessionPrincipal admin, UUID returnId, OnlineReturnStatus decision, CountDownLatch ready, CountDownLatch release) throws Exception {
		ready.countDown(); release.await(5, TimeUnit.SECONDS);
		try { onlineOrderService.decideReturn(admin, returnId, new ReturnDecision(UUID.randomUUID(), decision, decision == OnlineReturnStatus.RECHAZADA ? "Rechazo sintético de carrera." : null)); return new Attempt(true, null); }
		catch (ApiException exception) { return new Attempt(false, exception.code()); }
	}

	private SessionPrincipal administrator() { return new SessionPrincipal(identityAccessService.authenticateStaff("veterinaria-aurora", "administracion@demo.invalid", required("AV_DEMO_AURORA_PASSWORD")).context()); }
	private SessionPrincipal client() { return new SessionPrincipal(identityAccessService.authenticateClient("veterinaria-aurora", required("AV_DEMO_CLIENT_AURORA_EMAIL"), required("AV_DEMO_CLIENT_AURORA_PASSWORD")).context()); }
	private String required(String name) { String value = System.getenv(name); assertNotNull(value, "La prueba requiere la variable local " + name + '.'); return value; }
	private record Attempt(boolean succeeded, String errorCode) { }
}
