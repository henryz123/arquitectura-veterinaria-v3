package pe.arquitecturaveterinaria.inventorysales.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
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
import pe.arquitecturaveterinaria.identityaccess.application.TenantSession;

/** Two simultaneous inspections of one physical return must restore stock only once. */
@SpringBootTest
class PosReturnConcurrencyCommitIntegrationTest {

	@Autowired private CatalogService catalogService;
	@Autowired private StockService stockService;
	@Autowired private PointOfSaleService pointOfSaleService;
	@Autowired private IdentityAccessService identityAccessService;

	private UUID tenantId;
	private UUID productId;
	private UUID saleId;
	private UUID returnId;

	@Test
	void concurrentInspectionsRestoreStockExactlyOnce() throws Exception {
		try {
			SessionPrincipal administrator = administrator();
			SessionPrincipal cashier = cashier(administrator);
			tenantId = administrator.session().tenantId();
			CatalogProduct product = catalogService.createProduct(administrator,
					new CatalogProductDraft(null, "B04-RETURN-RACE-" + UUID.randomUUID().toString().substring(0, 8),
							"Producto devolución concurrente", null, new BigDecimal("9.00"), "ACTIVO_NO_PUBLICADO", true, null));
			productId = product.id();
			stockService.adjust(administrator, productId, new StockAdjustmentDraft(1, "Saldo para devolución concurrente", UUID.randomUUID()));
			PosSale sale = pointOfSaleService.createSale(cashier,
					new PosSaleDraft(UUID.randomUUID(), List.of(new PosSaleLineDraft(productId, 1))));
			sale = pointOfSaleService.confirmSale(cashier, sale.id(), new PosSaleConfirmationDraft("EFECTIVO", new BigDecimal("9.00")));
			saleId = sale.id();
			PosReturn created = pointOfSaleService.requestReturn(cashier, saleId,
					new PosReturnDraft(UUID.randomUUID(), "Devolución concurrente sintética", List.of(new PosReturnLineDraft(sale.lines().getFirst().id(), 1))));
			created = pointOfSaleService.receiveReturn(cashier, created.id(),
					List.of(new PosReturnReceiptLineDraft(created.lines().getFirst().id(), 1)));
			returnId = created.id();
			UUID returnLineId = created.lines().getFirst().id();

			CountDownLatch ready = new CountDownLatch(2);
			CountDownLatch start = new CountDownLatch(1);
			ExecutorService executor = Executors.newFixedThreadPool(2);
			try {
				Future<PosReturn> first = executor.submit(() -> inspectWhenReleased(cashier, returnLineId, ready, start));
				Future<PosReturn> second = executor.submit(() -> inspectWhenReleased(cashier, returnLineId, ready, start));
				assertTrue(ready.await(5, TimeUnit.SECONDS), "Las dos inspecciones deben quedar listas antes de competir.");
				start.countDown();
				assertEquals("INSPECCIONADA_APTA", first.get(10, TimeUnit.SECONDS).status());
				assertEquals("INSPECCIONADA_APTA", second.get(10, TimeUnit.SECONDS).status());
			} finally {
				executor.shutdownNow();
			}

			assertEquals(1, available(administrator));
			assertEquals(3, stockService.listMovements(administrator, productId).size());
			assertEquals(1, pointOfSaleService.findReturn(cashier, returnId).lines().getFirst().restockedQuantity());
		} finally {
			removeCommittedFixture();
		}
	}

	@AfterEach
	void removeCommittedFixture() throws SQLException {
		if (productId == null) return;
		try (Connection connection = DriverManager.getConnection(required("AV_DB_URL"), required("AV_MIGRATION_DB_USER"), required("AV_MIGRATION_DB_PASSWORD"))) {
			connection.setAutoCommit(false);
			try (PreparedStatement movements = connection.prepareStatement("delete from inventory_sales.stock_movement where tenant_id = ? and product_id = ?");
					PreparedStatement returnLines = connection.prepareStatement("delete from inventory_sales.pos_return_line where tenant_id = ? and return_id = ?");
					PreparedStatement returns = connection.prepareStatement("delete from inventory_sales.pos_return where tenant_id = ? and id = ?");
					PreparedStatement payments = connection.prepareStatement("delete from inventory_sales.pos_manual_payment where tenant_id = ? and sale_id = ?");
					PreparedStatement saleLines = connection.prepareStatement("delete from inventory_sales.pos_sale_line where tenant_id = ? and sale_id = ?");
					PreparedStatement sales = connection.prepareStatement("delete from inventory_sales.pos_sale where tenant_id = ? and id = ?");
					PreparedStatement balance = connection.prepareStatement("delete from inventory_sales.stock_balance where tenant_id = ? and product_id = ?");
					PreparedStatement product = connection.prepareStatement("delete from inventory_sales.product where tenant_id = ? and id = ?")) {
				movements.setObject(1, tenantId); movements.setObject(2, productId); movements.executeUpdate();
				returnLines.setObject(1, tenantId); returnLines.setObject(2, returnId); returnLines.executeUpdate();
				returns.setObject(1, tenantId); returns.setObject(2, returnId); returns.executeUpdate();
				payments.setObject(1, tenantId); payments.setObject(2, saleId); payments.executeUpdate();
				saleLines.setObject(1, tenantId); saleLines.setObject(2, saleId); saleLines.executeUpdate();
				sales.setObject(1, tenantId); sales.setObject(2, saleId); sales.executeUpdate();
				balance.setObject(1, tenantId); balance.setObject(2, productId); balance.executeUpdate();
				product.setObject(1, tenantId); product.setObject(2, productId);
				assertEquals(1, product.executeUpdate(), "El fixture de devolución concurrente debe eliminar exactamente su producto.");
			}
			assertEquals(0, remainingProduct(connection));
			connection.commit();
		} finally {
			productId = null;
		}
	}

	private PosReturn inspectWhenReleased(SessionPrincipal cashier, UUID returnLineId, CountDownLatch ready, CountDownLatch start) throws Exception {
		ready.countDown();
		assertTrue(start.await(5, TimeUnit.SECONDS), "La barrera de inspección no fue liberada.");
		return pointOfSaleService.inspectReturn(cashier, returnId, List.of(new PosReturnInspectionLineDraft(returnLineId, 1)));
	}

	private int available(SessionPrincipal administrator) {
		return stockService.listBalances(administrator).stream().filter(balance -> balance.productId().equals(productId)).findFirst().orElseThrow().availableQuantity();
	}

	private int remainingProduct(Connection connection) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("select count(*) from inventory_sales.product where tenant_id = ? and id = ?")) {
			statement.setObject(1, tenantId);
			statement.setObject(2, productId);
			try (ResultSet result = statement.executeQuery()) {
				result.next();
				return result.getInt(1);
			}
		}
	}

	private SessionPrincipal administrator() {
		String password = required("AV_DEMO_AURORA_PASSWORD");
		return new SessionPrincipal(identityAccessService.authenticateStaff("veterinaria-aurora", "administracion@demo.invalid", password).context());
	}

	private SessionPrincipal cashier(SessionPrincipal source) {
		TenantSession session = source.session();
		return new SessionPrincipal(new TenantSession(session.sessionId(), session.accountId(), session.tenantId(), session.tenantSlug(),
				session.tenantCommercialName(), session.tenantStatus(), session.accountType(), List.of("CAJERO"),
				session.sessionTokenHash(), Instant.now().plusSeconds(3600)));
	}

	private String required(String name) {
		String value = System.getenv(name);
		assertNotNull(value, "La prueba requiere la variable local " + name + ".");
		return value;
	}
}
