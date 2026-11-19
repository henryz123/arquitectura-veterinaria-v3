package pe.arquitecturaveterinaria.inventorysales.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import pe.arquitecturaveterinaria.shared.api.ApiException;

/** Commits two competing confirmations and proves the database admits at most one active charge. */
@SpringBootTest
class PosServiceChargeConcurrencyCommitIntegrationTest {

	@Autowired private CatalogService catalogService;
	@Autowired private PointOfSaleService pointOfSaleService;
	@Autowired private IdentityAccessService identityAccessService;

	private UUID tenantId;
	private UUID productId;
	private UUID firstSaleId;
	private UUID secondSaleId;

	@Test
	void concurrentConfirmationsOfOnePrestationCommitAtMostOneActiveCharge() throws Exception {
		try {
			SessionPrincipal administrator = administrator();
			SessionPrincipal cashier = cashier(administrator);
			tenantId = administrator.session().tenantId();
			ServiceChargeCandidate prestation = pointOfSaleService.listEligibleServiceCharges(cashier).stream().findFirst().orElseThrow(
					() -> new AssertionError("La base local requiere al menos una prestación finalizada sin cobro para esta prueba controlada."));
			CatalogProduct service = catalogService.createProduct(administrator,
					new CatalogProductDraft(null, "B04-RF015-RACE-" + UUID.randomUUID().toString().substring(0, 8),
							"Servicio concurrente RF015", null, new BigDecimal("13.00"), "ACTIVO_NO_PUBLICADO", false, null));
			productId = service.id();
			firstSaleId = pointOfSaleService.createSale(cashier,
					new PosSaleDraft(UUID.randomUUID(), List.of(new PosSaleLineDraft(productId, 1)))).id();
			secondSaleId = pointOfSaleService.createSale(cashier,
					new PosSaleDraft(UUID.randomUUID(), List.of(new PosSaleLineDraft(productId, 1)))).id();

			CountDownLatch ready = new CountDownLatch(2);
			CountDownLatch start = new CountDownLatch(1);
			ExecutorService executor = Executors.newFixedThreadPool(2);
			try {
				Future<String> first = executor.submit(() -> confirmWhenReleased(cashier, firstSaleId, service, prestation, ready, start));
				Future<String> second = executor.submit(() -> confirmWhenReleased(cashier, secondSaleId, service, prestation, ready, start));
				assertTrue(ready.await(5, TimeUnit.SECONDS), "Las dos confirmaciones deben esperar la misma barrera.");
				start.countDown();
				List<String> outcomes = List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
				assertEquals(1, outcomes.stream().filter("COMPLETADA"::equals).count());
				assertEquals(1, outcomes.stream().filter("B04_SERVICE_ALREADY_CHARGED"::equals).count());
			} finally {
				executor.shutdownNow();
			}

			assertEquals(1, activeChargesFor(prestation));
			assertEquals(1, List.of(firstSaleId, secondSaleId).stream()
					.filter(saleId -> "COMPLETADA".equals(pointOfSaleService.findSale(cashier, saleId).status())).count());
		} finally {
			removeCommittedFixture();
		}
	}

	@AfterEach
	void removeCommittedFixture() throws SQLException {
		if (productId == null) return;
		try (Connection connection = DriverManager.getConnection(required("AV_DB_URL"), required("AV_MIGRATION_DB_USER"),
				required("AV_MIGRATION_DB_PASSWORD"))) {
			connection.setAutoCommit(false);
			try (PreparedStatement charges = connection.prepareStatement("""
					delete from inventory_sales.pos_service_charge charge
					using inventory_sales.pos_sale_line sale_line
					where charge.tenant_id = sale_line.tenant_id and charge.sale_line_id = sale_line.id
					  and sale_line.tenant_id = ? and sale_line.sale_id in (?, ?)
					""");
					PreparedStatement payments = connection.prepareStatement(
							"delete from inventory_sales.pos_manual_payment where tenant_id = ? and sale_id in (?, ?)");
					PreparedStatement lines = connection.prepareStatement(
							"delete from inventory_sales.pos_sale_line where tenant_id = ? and sale_id in (?, ?)");
					PreparedStatement sales = connection.prepareStatement(
							"delete from inventory_sales.pos_sale where tenant_id = ? and id in (?, ?)");
					PreparedStatement balance = connection.prepareStatement(
							"delete from inventory_sales.stock_balance where tenant_id = ? and product_id = ?");
					PreparedStatement product = connection.prepareStatement(
							"delete from inventory_sales.product where tenant_id = ? and id = ?")) {
				bindSales(charges); charges.executeUpdate();
				bindSales(payments); payments.executeUpdate();
				bindSales(lines); lines.executeUpdate();
				bindSales(sales); sales.executeUpdate();
				balance.setObject(1, tenantId); balance.setObject(2, productId); balance.executeUpdate();
				product.setObject(1, tenantId); product.setObject(2, productId);
				assertEquals(1, product.executeUpdate(), "La prueba concurrente debe eliminar únicamente su producto sintético.");
			}
			assertEquals(0, remainingProduct(connection));
			connection.commit();
		} finally {
			productId = null;
		}
	}

	private String confirmWhenReleased(SessionPrincipal cashier, UUID saleId, CatalogProduct service, ServiceChargeCandidate prestation,
			CountDownLatch ready, CountDownLatch start) throws Exception {
		ready.countDown();
		assertTrue(start.await(5, TimeUnit.SECONDS), "La barrera de confirmación no fue liberada.");
		try {
			return pointOfSaleService.confirmSale(cashier, saleId, new PosSaleConfirmationDraft("EFECTIVO", service.salePricePen(),
					List.of(new PosServiceChargeDraft(service.id(), prestation.prestationType(), prestation.prestationId())))).status();
		} catch (ApiException exception) {
			return exception.code();
		}
	}

	private int activeChargesFor(ServiceChargeCandidate prestation) throws SQLException {
		String statement = switch (prestation.prestationType()) {
			case CLINICA -> "select count(*) from inventory_sales.pos_service_charge where status = 'COBRADO' and clinical_episode_id = ?";
			case GROOMING -> "select count(*) from inventory_sales.pos_service_charge where status = 'COBRADO' and grooming_attention_id = ?";
		};
		try (Connection connection = DriverManager.getConnection(required("AV_DB_URL"), required("AV_MIGRATION_DB_USER"),
				required("AV_MIGRATION_DB_PASSWORD"));
				PreparedStatement query = connection.prepareStatement(statement)) {
			query.setObject(1, prestation.prestationId());
			try (ResultSet result = query.executeQuery()) {
				assertTrue(result.next());
				return result.getInt(1);
			}
		}
	}

	private void bindSales(PreparedStatement statement) throws SQLException {
		statement.setObject(1, tenantId);
		statement.setObject(2, firstSaleId);
		statement.setObject(3, secondSaleId);
	}

	private int remainingProduct(Connection connection) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("select count(*) from inventory_sales.product where tenant_id = ? and id = ?")) {
			statement.setObject(1, tenantId); statement.setObject(2, productId);
			try (ResultSet result = statement.executeQuery()) {
				assertTrue(result.next());
				return result.getInt(1);
			}
		}
	}

	private SessionPrincipal administrator() {
		return new SessionPrincipal(identityAccessService.authenticateStaff("veterinaria-aurora", "administracion@demo.invalid",
				required("AV_DEMO_AURORA_PASSWORD")).context());
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
