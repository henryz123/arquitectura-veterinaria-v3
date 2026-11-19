package pe.arquitecturaveterinaria.inventorysales.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.ProposalDecision;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.ProposalDraft;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.ProposalLineDraft;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.ProposalStatus;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.StaffOrder;
import pe.arquitecturaveterinaria.shared.api.ApiException;

/** B05-C2 keeps immutable original orders while a client explicitly decides a staff proposal. */
@SpringBootTest
@Transactional(transactionManager = "jdbcTransactionManager")
class OnlineOrderProposalDatabaseIntegrationTest {

	@Autowired private CatalogService catalogService;
	@Autowired private StockService stockService;
	@Autowired private OnlineOrderService onlineOrderService;
	@Autowired private IdentityAccessService identityAccessService;

	@Test
	void acceptsReplacementTermsThenConfirmsOnlyTheirStockOnce() {
		SessionPrincipal admin = administrator();
		SessionPrincipal client = client();
		CatalogProduct product = product(admin, "accept", new BigDecimal("10.00"), 1);
		Order original = onlineOrderService.create(client, orderDraft(product, 2));

		assertEquals("EN_REVISION", onlineOrderService.confirm(admin, original.id()).status());
		StaffOrder proposed = onlineOrderService.propose(admin, original.id(), proposalDraft(product, 1, product.salePricePen(), "Se propone una unidad disponible."));
		assertEquals("CAMBIO_PROPUESTO", proposed.status());
		assertEquals(2, proposed.proposals().getFirst().previousTerms().lines().getFirst().quantity(), "La solicitud original no se sobrescribe.");
		assertEquals(1, proposed.proposals().getFirst().proposedTerms().lines().getFirst().quantity());

		Order accepted = onlineOrderService.decideProposal(client, proposed.proposals().getFirst().id(), new ProposalDecision(UUID.randomUUID(), ProposalStatus.ACEPTADA));
		assertEquals("EN_REVISION", accepted.status());
		assertEquals(ProposalStatus.ACEPTADA, accepted.proposals().getFirst().status());
		assertEquals(2, accepted.lines().getFirst().quantity(), "Las líneas inmutables del pedido original se conservan.");
		int movementsBefore = stockService.listMovements(admin, product.id()).size();

		assertEquals("CONFIRMADO", onlineOrderService.confirm(admin, original.id()).status());
		assertEquals(0, balance(admin, product.id()));
		assertEquals(movementsBefore + 1, stockService.listMovements(admin, product.id()).size());
		assertEquals("CONFIRMADO", onlineOrderService.confirm(admin, original.id()).status());
		assertEquals(0, balance(admin, product.id()));
		assertEquals(movementsBefore + 1, stockService.listMovements(admin, product.id()).size(), "Reintentar no duplica la salida de stock.");
	}

	@Test
	void rejectionAndRepeatedDecisionLeaveStockUntouchedAndAllowAnotherProposal() {
		SessionPrincipal admin = administrator();
		SessionPrincipal client = client();
		CatalogProduct product = product(admin, "reject", new BigDecimal("8.50"), 1);
		Order original = onlineOrderService.create(client, orderDraft(product, 2));
		onlineOrderService.confirm(admin, original.id());
		StaffOrder proposed = onlineOrderService.propose(admin, original.id(), proposalDraft(product, 1, product.salePricePen(), "Solo queda una unidad disponible."));
		UUID decisionOperation = UUID.randomUUID();
		int movementsBefore = stockService.listMovements(admin, product.id()).size();

		Order rejected = onlineOrderService.decideProposal(client, proposed.proposals().getFirst().id(), new ProposalDecision(decisionOperation, ProposalStatus.RECHAZADA));
		assertEquals("EN_REVISION", rejected.status());
		assertEquals(ProposalStatus.RECHAZADA, rejected.proposals().getFirst().status());
		assertEquals(1, balance(admin, product.id()));
		assertEquals(movementsBefore, stockService.listMovements(admin, product.id()).size());
		assertEquals("EN_REVISION", onlineOrderService.decideProposal(client, proposed.proposals().getFirst().id(), new ProposalDecision(decisionOperation, ProposalStatus.RECHAZADA)).status());

		ApiException conflictingDecision = assertThrows(ApiException.class, () -> onlineOrderService.decideProposal(client, proposed.proposals().getFirst().id(), new ProposalDecision(UUID.randomUUID(), ProposalStatus.ACEPTADA)));
		assertEquals(HttpStatus.CONFLICT, conflictingDecision.status());
		assertEquals("B05_PROPOSAL_STATE", conflictingDecision.code());
		assertEquals("CAMBIO_PROPUESTO", onlineOrderService.propose(admin, original.id(), proposalDraft(product, 1, product.salePricePen(), "Nueva propuesta después del rechazo.")).status());
		assertEquals(movementsBefore, stockService.listMovements(admin, product.id()).size(), "Una respuesta de cliente no reserva ni descuenta stock.");
	}

	@Test
	void acceptedProposalReturnsToReviewWithoutPartialStockWhenAvailabilityChanges() {
		SessionPrincipal admin = administrator();
		SessionPrincipal client = client();
		CatalogProduct product = product(admin, "late-stock", new BigDecimal("12.00"), 1);
		Order original = onlineOrderService.create(client, orderDraft(product, 2));
		onlineOrderService.confirm(admin, original.id());
		StaffOrder proposed = onlineOrderService.propose(admin, original.id(), proposalDraft(product, 1, product.salePricePen(), "Se propuso una unidad disponible."));
		onlineOrderService.decideProposal(client, proposed.proposals().getFirst().id(), new ProposalDecision(UUID.randomUUID(), ProposalStatus.ACEPTADA));
		stockService.adjust(admin, product.id(), new StockAdjustmentDraft(-1, "Consumo sintético entre aceptación y confirmación", UUID.randomUUID()));
		int movementsAfterIndependentAdjustment = stockService.listMovements(admin, product.id()).size();

		StaffOrder reviewedAgain = onlineOrderService.confirm(admin, original.id());
		assertEquals("EN_REVISION", reviewedAgain.status());
		assertEquals(ProposalStatus.ACEPTADA, reviewedAgain.proposals().getFirst().status(), "La aceptación se conserva como evidencia histórica.");
		assertEquals(0, balance(admin, product.id()));
		assertEquals(movementsAfterIndependentAdjustment, stockService.listMovements(admin, product.id()).size(), "La revalidación no deja una salida parcial.");
	}

	@Test
	void cashierCannotChangeCataloguePriceButAdministratorCanProposeItExplicitly() {
		SessionPrincipal admin = administrator();
		SessionPrincipal cashier = cashier();
		SessionPrincipal client = client();
		CatalogProduct product = product(admin, "price", new BigDecimal("15.00"), 1);
		Order original = onlineOrderService.create(client, orderDraft(product, 2));
		onlineOrderService.confirm(admin, original.id());

		ApiException denied = assertThrows(ApiException.class, () -> onlineOrderService.propose(cashier, original.id(), proposalDraft(product, 1, new BigDecimal("14.00"), "Precio excepcional solicitado.")));
		assertEquals(HttpStatus.FORBIDDEN, denied.status());
		assertEquals("B05_PRICE_PROPOSAL_ADMIN_REQUIRED", denied.code());
		StaffOrder proposed = onlineOrderService.propose(admin, original.id(), proposalDraft(product, 1, new BigDecimal("14.00"), "Precio excepcional autorizado."));
		assertEquals(new BigDecimal("14.00"), proposed.proposals().getFirst().proposedTerms().lines().getFirst().unitPricePen());
	}

	private CatalogProduct product(SessionPrincipal admin, String label, BigDecimal price, int quantity) {
		String suffix = UUID.randomUUID().toString().substring(0, 8);
		CatalogProduct product = catalogService.createProduct(admin, new CatalogProductDraft(null, "B05-C2-" + label + '-' + suffix,
				"Propuesta C2 " + label + ' ' + suffix, null, price, "ACTIVO_PUBLICADO", true, null));
		stockService.adjust(admin, product.id(), new StockAdjustmentDraft(quantity, "Saldo sintético B05-C2", UUID.randomUUID()));
		return product;
	}

	private Draft orderDraft(CatalogProduct product, int quantity) {
		return new Draft(UUID.randomUUID(), DeliveryMode.RECOJO, "999 000 111", null, null,
				List.of(new LineDraft(product.id(), quantity, product.salePricePen())));
	}

	private ProposalDraft proposalDraft(CatalogProduct product, int quantity, BigDecimal price, String reason) {
		return new ProposalDraft(UUID.randomUUID(), reason, DeliveryMode.RECOJO, "999 000 111", null,
				List.of(new ProposalLineDraft(product.id(), quantity, price)));
	}

	private int balance(SessionPrincipal admin, UUID productId) {
		return stockService.listBalances(admin).stream().filter(item -> item.productId().equals(productId)).findFirst().orElseThrow().availableQuantity();
	}

	private SessionPrincipal administrator() { return staff("administracion@demo.invalid", "AV_DEMO_AURORA_PASSWORD"); }
	private SessionPrincipal cashier() { return staff(required("AV_DEMO_CASHIER_AURORA_EMAIL"), "AV_DEMO_CASHIER_AURORA_PASSWORD"); }
	private SessionPrincipal staff(String email, String passwordVariable) { return new SessionPrincipal(identityAccessService.authenticateStaff("veterinaria-aurora", email, required(passwordVariable)).context()); }
	private SessionPrincipal client() { return new SessionPrincipal(identityAccessService.authenticateClient("veterinaria-aurora", required("AV_DEMO_CLIENT_AURORA_EMAIL"), required("AV_DEMO_CLIENT_AURORA_PASSWORD")).context()); }
	private String required(String name) { String value = System.getenv(name); Assertions.assertNotNull(value, "La prueba requiere la variable local " + name + '.'); Assertions.assertFalse(value.isBlank(), "La variable local no puede estar vacía."); return value; }
}
