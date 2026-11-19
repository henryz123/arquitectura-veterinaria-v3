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
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import pe.arquitecturaveterinaria.identityaccess.application.IdentityAccessService;
import pe.arquitecturaveterinaria.identityaccess.application.SessionPrincipal;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.DeliveryMode;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.Draft;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.LineDraft;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.ProposalDecision;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.ProposalDraft;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.ProposalLineDraft;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.ProposalStatus;
import pe.arquitecturaveterinaria.shared.api.ApiException;

/** Two competing client decisions leave one auditable result and never reserve inventory. */
@SpringBootTest
class OnlineOrderProposalDecisionConcurrencyCommitIntegrationTest {

	@Autowired private CatalogService catalogService;
	@Autowired private StockService stockService;
	@Autowired private OnlineOrderService onlineOrderService;
	@Autowired private IdentityAccessService identityAccessService;

	private UUID tenantId;
	private UUID productId;
	private UUID orderId;

	@Test
	void simultaneousDecisionsResolveOnlyOneProposalStateWithoutStockMutation() throws Exception {
		SessionPrincipal admin = administrator();
		SessionPrincipal client = client();
		tenantId = admin.session().tenantId();
		String suffix = UUID.randomUUID().toString().substring(0, 8);
		CatalogProduct product = catalogService.createProduct(admin, new CatalogProductDraft(null, "B05-C2-RACE-" + suffix,
				"Carrera de decisión C2 " + suffix, null, new BigDecimal("9.00"), "ACTIVO_PUBLICADO", true, null));
		productId = product.id();
		stockService.adjust(admin, productId, new StockAdjustmentDraft(1, "Saldo de carrera B05-C2", UUID.randomUUID()));
		var order = onlineOrderService.create(client, new Draft(UUID.randomUUID(), DeliveryMode.RECOJO, "999 000 111", null, null,
				List.of(new LineDraft(productId, 2, product.salePricePen()))));
		orderId = order.id();
		onlineOrderService.confirm(admin, orderId);
		var proposed = onlineOrderService.propose(admin, orderId, new ProposalDraft(UUID.randomUUID(), "Una unidad disponible.", DeliveryMode.RECOJO,
				"999 000 111", null, List.of(new ProposalLineDraft(productId, 1, product.salePricePen()))));
		UUID proposalId = proposed.proposals().getFirst().id();
		int movementsBefore = stockService.listMovements(admin, productId).size();

		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch release = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<Attempt> accept = executor.submit(() -> decideWhenReleased(client, proposalId, ProposalStatus.ACEPTADA, ready, release));
			Future<Attempt> reject = executor.submit(() -> decideWhenReleased(client, proposalId, ProposalStatus.RECHAZADA, ready, release));
			assertTrue(ready.await(5, TimeUnit.SECONDS));
			release.countDown();
			Attempt accepted = accept.get(10, TimeUnit.SECONDS);
			Attempt rejected = reject.get(10, TimeUnit.SECONDS);
			assertEquals(1, (accepted.succeeded() ? 1 : 0) + (rejected.succeeded() ? 1 : 0), accepted + " / " + rejected);
			assertTrue((accepted.succeeded() && "B05_PROPOSAL_STATE".equals(rejected.errorCode()))
					|| (rejected.succeeded() && "B05_PROPOSAL_STATE".equals(accepted.errorCode())), accepted + " / " + rejected);
		} finally {
			executor.shutdownNow();
		}

		var result = onlineOrderService.findOwn(client, orderId);
		assertEquals("EN_REVISION", result.status());
		assertEquals(1, result.proposals().size());
		assertTrue(result.proposals().getFirst().status() == ProposalStatus.ACEPTADA || result.proposals().getFirst().status() == ProposalStatus.RECHAZADA);
		assertEquals(movementsBefore, stockService.listMovements(admin, productId).size(), "Una decisión no descuenta ni reserva stock.");
	}

	@AfterEach
	void removeCommittedFixture() throws SQLException {
		if (tenantId == null || productId == null) return;
		try (Connection connection = DriverManager.getConnection(required("AV_DB_URL"), required("AV_MIGRATION_DB_USER"), required("AV_MIGRATION_DB_PASSWORD"))) {
			connection.setAutoCommit(false);
			try (PreparedStatement movements = connection.prepareStatement("delete from inventory_sales.stock_movement where tenant_id = ? and product_id = ?");
					PreparedStatement proposalLines = connection.prepareStatement("delete from inventory_sales.online_order_proposal_line where tenant_id = ? and proposal_id in (select id from inventory_sales.online_order_proposal where tenant_id = ? and order_id = ?)");
					PreparedStatement proposals = connection.prepareStatement("delete from inventory_sales.online_order_proposal where tenant_id = ? and order_id = ?");
					PreparedStatement events = connection.prepareStatement("delete from inventory_sales.online_order_status_event where tenant_id = ? and order_id = ?");
					PreparedStatement orderLines = connection.prepareStatement("delete from inventory_sales.online_order_line where tenant_id = ? and order_id = ?");
					PreparedStatement orders = connection.prepareStatement("delete from inventory_sales.online_order where tenant_id = ? and id = ?");
					PreparedStatement balance = connection.prepareStatement("delete from inventory_sales.stock_balance where tenant_id = ? and product_id = ?");
					PreparedStatement product = connection.prepareStatement("delete from inventory_sales.product where tenant_id = ? and id = ?")) {
				movements.setObject(1, tenantId); movements.setObject(2, productId); movements.executeUpdate();
				proposalLines.setObject(1, tenantId); proposalLines.setObject(2, tenantId); proposalLines.setObject(3, orderId); proposalLines.executeUpdate();
				proposals.setObject(1, tenantId); proposals.setObject(2, orderId); proposals.executeUpdate();
				events.setObject(1, tenantId); events.setObject(2, orderId); events.executeUpdate();
				orderLines.setObject(1, tenantId); orderLines.setObject(2, orderId); orderLines.executeUpdate();
				orders.setObject(1, tenantId); orders.setObject(2, orderId); orders.executeUpdate();
				balance.setObject(1, tenantId); balance.setObject(2, productId); balance.executeUpdate();
				product.setObject(1, tenantId); product.setObject(2, productId); product.executeUpdate();
				connection.commit();
			} catch (SQLException exception) { connection.rollback(); throw exception; }
		}
	}

	private Attempt decideWhenReleased(SessionPrincipal client, UUID proposalId, ProposalStatus decision, CountDownLatch ready, CountDownLatch release) throws Exception {
		ready.countDown();
		release.await(5, TimeUnit.SECONDS);
		try { return new Attempt(true, onlineOrderService.decideProposal(client, proposalId, new ProposalDecision(UUID.randomUUID(), decision)).status(), null); }
		catch (ApiException exception) { return new Attempt(false, null, exception.code()); }
	}

	private SessionPrincipal administrator() { return new SessionPrincipal(identityAccessService.authenticateStaff("veterinaria-aurora", "administracion@demo.invalid", required("AV_DEMO_AURORA_PASSWORD")).context()); }
	private SessionPrincipal client() { return new SessionPrincipal(identityAccessService.authenticateClient("veterinaria-aurora", required("AV_DEMO_CLIENT_AURORA_EMAIL"), required("AV_DEMO_CLIENT_AURORA_PASSWORD")).context()); }
	private String required(String name) { String value = System.getenv(name); assertNotNull(value, "La prueba requiere la variable local " + name + '.'); return value; }
	private record Attempt(boolean succeeded, String status, String errorCode) { }
}
