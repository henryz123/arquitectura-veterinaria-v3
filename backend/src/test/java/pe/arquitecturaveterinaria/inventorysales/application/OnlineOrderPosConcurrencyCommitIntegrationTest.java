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
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.DeliveryMode;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.Draft;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.LineDraft;
import pe.arquitecturaveterinaria.shared.api.ApiException;

/** A committed B05 confirmation and a committed POS sale compete for one B04 balance without double consumption. */
@SpringBootTest
class OnlineOrderPosConcurrencyCommitIntegrationTest {

	@Autowired private CatalogService catalogService;
	@Autowired private StockService stockService;
	@Autowired private PointOfSaleService pointOfSaleService;
	@Autowired private OnlineOrderService onlineOrderService;
	@Autowired private IdentityAccessService identityAccessService;

	private UUID tenantId;
	private UUID productId;
	private UUID orderId;
	private UUID saleId;

	@Test
	void onlineConfirmationAndPosCannotConsumeTheSameLastUnitTwice() throws Exception {
		SessionPrincipal admin = administrator();
		SessionPrincipal cashier = cashier();
		SessionPrincipal client = client();
		tenantId = admin.session().tenantId();
		String suffix = UUID.randomUUID().toString().substring(0, 8);
		CatalogProduct product = catalogService.createProduct(admin, new CatalogProductDraft(null, "B05-C1-RACE-" + suffix,
				"Carrera online POS " + suffix, null, new BigDecimal("9.00"), "ACTIVO_PUBLICADO", true, null));
		productId = product.id();
		stockService.adjust(admin, productId, new StockAdjustmentDraft(1, "Saldo de carrera B05-C1", UUID.randomUUID()));
		var order = onlineOrderService.create(client, new Draft(UUID.randomUUID(), DeliveryMode.RECOJO, "999 000 111", null, null,
				List.of(new LineDraft(productId, 1, product.salePricePen()))));
		orderId = order.id();
		var sale = pointOfSaleService.createSale(cashier, new PosSaleDraft(UUID.randomUUID(), List.of(new PosSaleLineDraft(productId, 1))));
		saleId = sale.id();

		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<OnlineAttempt> online = executor.submit(() -> confirmOnlineWhenReleased(admin, orderId, ready, start));
			Future<PosAttempt> pos = executor.submit(() -> confirmPosWhenReleased(cashier, saleId, ready, start));
			assertTrue(ready.await(5, TimeUnit.SECONDS));
			start.countDown();
			OnlineAttempt onlineResult = online.get(10, TimeUnit.SECONDS);
			PosAttempt posResult = pos.get(10, TimeUnit.SECONDS);
			assertTrue("CONFIRMADO".equals(onlineResult.status()) || "EN_REVISION".equals(onlineResult.status()), onlineResult.toString());
			assertTrue(posResult.completed() || "B04_STOCK_INSUFFICIENT".equals(posResult.errorCode()), posResult.toString());
			assertEquals(1, ("CONFIRMADO".equals(onlineResult.status()) ? 1 : 0) + (posResult.completed() ? 1 : 0));
		} finally {
			executor.shutdownNow();
		}

		assertEquals(0, stockService.listBalances(admin).stream().filter(item -> item.productId().equals(productId)).findFirst().orElseThrow().availableQuantity());
		assertEquals(2, stockService.listMovements(admin, productId).size(), "Solo el alta sintética y un único consumo deben existir.");
	}

	@AfterEach
	void removeCommittedFixture() throws SQLException {
		if (tenantId == null || productId == null) return;
		try (Connection connection = DriverManager.getConnection(required("AV_DB_URL"), required("AV_MIGRATION_DB_USER"), required("AV_MIGRATION_DB_PASSWORD"))) {
			connection.setAutoCommit(false);
			try (PreparedStatement movements = connection.prepareStatement("delete from inventory_sales.stock_movement where tenant_id = ? and product_id = ?");
					PreparedStatement events = connection.prepareStatement("delete from inventory_sales.online_order_status_event where tenant_id = ? and order_id = ?");
					PreparedStatement orderLines = connection.prepareStatement("delete from inventory_sales.online_order_line where tenant_id = ? and order_id = ?");
					PreparedStatement orders = connection.prepareStatement("delete from inventory_sales.online_order where tenant_id = ? and id = ?");
					PreparedStatement payments = connection.prepareStatement("delete from inventory_sales.pos_manual_payment where tenant_id = ? and sale_id = ?");
					PreparedStatement saleLines = connection.prepareStatement("delete from inventory_sales.pos_sale_line where tenant_id = ? and sale_id = ?");
					PreparedStatement sales = connection.prepareStatement("delete from inventory_sales.pos_sale where tenant_id = ? and id = ?");
					PreparedStatement balance = connection.prepareStatement("delete from inventory_sales.stock_balance where tenant_id = ? and product_id = ?");
					PreparedStatement product = connection.prepareStatement("delete from inventory_sales.product where tenant_id = ? and id = ?")) {
				movements.setObject(1, tenantId); movements.setObject(2, productId); movements.executeUpdate();
				if (orderId != null) { events.setObject(1, tenantId); events.setObject(2, orderId); events.executeUpdate(); orderLines.setObject(1, tenantId); orderLines.setObject(2, orderId); orderLines.executeUpdate(); orders.setObject(1, tenantId); orders.setObject(2, orderId); orders.executeUpdate(); }
				if (saleId != null) { payments.setObject(1, tenantId); payments.setObject(2, saleId); payments.executeUpdate(); saleLines.setObject(1, tenantId); saleLines.setObject(2, saleId); saleLines.executeUpdate(); sales.setObject(1, tenantId); sales.setObject(2, saleId); sales.executeUpdate(); }
				balance.setObject(1, tenantId); balance.setObject(2, productId); balance.executeUpdate();
				product.setObject(1, tenantId); product.setObject(2, productId); product.executeUpdate();
				connection.commit();
			} catch (SQLException exception) { connection.rollback(); throw exception; }
		}
	}

	private OnlineAttempt confirmOnlineWhenReleased(SessionPrincipal admin, UUID id, CountDownLatch ready, CountDownLatch start) throws Exception {
		ready.countDown(); start.await(5, TimeUnit.SECONDS);
		try { return new OnlineAttempt(onlineOrderService.confirm(admin, id).status()); }
		catch (ApiException exception) { return new OnlineAttempt(exception.code()); }
	}
	private PosAttempt confirmPosWhenReleased(SessionPrincipal cashier, UUID id, CountDownLatch ready, CountDownLatch start) throws Exception {
		ready.countDown(); start.await(5, TimeUnit.SECONDS);
		try { pointOfSaleService.confirmSale(cashier, id, new PosSaleConfirmationDraft("EFECTIVO", new BigDecimal("9.00"), List.of())); return new PosAttempt(true, null); }
		catch (ApiException exception) { return new PosAttempt(false, exception.code()); }
	}
	private SessionPrincipal administrator() { return new SessionPrincipal(identityAccessService.authenticateStaff("veterinaria-aurora", "administracion@demo.invalid", required("AV_DEMO_AURORA_PASSWORD")).context()); }
	private SessionPrincipal cashier() { return new SessionPrincipal(identityAccessService.authenticateStaff("veterinaria-aurora", required("AV_DEMO_CASHIER_AURORA_EMAIL"), required("AV_DEMO_CASHIER_AURORA_PASSWORD")).context()); }
	private SessionPrincipal client() { return new SessionPrincipal(identityAccessService.authenticateClient("veterinaria-aurora", required("AV_DEMO_CLIENT_AURORA_EMAIL"), required("AV_DEMO_CLIENT_AURORA_PASSWORD")).context()); }
	private String required(String name) { String value = System.getenv(name); assertNotNull(value, "La regresión requiere la variable local " + name + "."); return value; }
	private record OnlineAttempt(String status) { }
	private record PosAttempt(boolean completed, String errorCode) { }
}
