package pe.arquitecturaveterinaria.inventorysales.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

import pe.arquitecturaveterinaria.identityaccess.application.IdentityAccessService;
import pe.arquitecturaveterinaria.identityaccess.application.SessionPrincipal;
import pe.arquitecturaveterinaria.identityaccess.application.TenantSession;
import pe.arquitecturaveterinaria.ownerpetcare.application.OwnerPetCareService;
import pe.arquitecturaveterinaria.ownerpetcare.application.OwnerPetCareService.ClinicalEpisodeCommand;
import pe.arquitecturaveterinaria.ownerpetcare.application.OwnerPetCareService.OwnerCommand;
import pe.arquitecturaveterinaria.ownerpetcare.application.OwnerPetCareService.PetCommand;
import pe.arquitecturaveterinaria.shared.api.ApiException;

/** V027 regression coverage for completed-prestation charges; each method rolls back its synthetic fixture. */
@SpringBootTest
@Transactional(transactionManager = "jdbcTransactionManager")
class PosServiceChargeDatabaseIntegrationTest {

	@Autowired private CatalogService catalogService;
	@Autowired private StockService stockService;
	@Autowired private PointOfSaleService pointOfSaleService;
	@Autowired private OwnerPetCareService ownerPetCareService;
	@Autowired private IdentityAccessService identityAccessService;

	@Test
	void confirmsAndReloadsOneChargeForOneCompletedClinicalEpisode() {
		SessionPrincipal administrator = administrator("veterinaria-aurora", "AV_DEMO_AURORA_PASSWORD");
		SessionPrincipal cashier = cashier(administrator);
		ServiceChargeCandidate prestation = completedClinicalPrestation(administrator);
		CatalogProduct service = nonStockService(administrator, "B04-RF015-CHARGE-");

		PosSale sale = pointOfSaleService.createSale(cashier,
				new PosSaleDraft(UUID.randomUUID(), List.of(new PosSaleLineDraft(service.id(), 1))));
		PosSale completed = pointOfSaleService.confirmSale(cashier, sale.id(),
				new PosSaleConfirmationDraft("EFECTIVO", service.salePricePen(),
						List.of(new PosServiceChargeDraft(service.id(), prestation.prestationType(), prestation.prestationId()))));

		assertEquals("COMPLETADA", completed.status());
		assertEquals(1, completed.serviceCharges().size());
		assertEquals("COBRADO", completed.serviceCharges().getFirst().status());
		assertEquals(prestation.prestationId(), completed.serviceCharges().getFirst().prestationId());
		assertEquals(prestation.prestationId(), pointOfSaleService.findSale(cashier, sale.id()).serviceCharges().getFirst().prestationId());
		assertTrue(pointOfSaleService.listEligibleServiceCharges(cashier).stream()
				.noneMatch(candidate -> candidate.prestationType() == prestation.prestationType()
						&& candidate.prestationId().equals(prestation.prestationId())));
	}

	@Test
	void rejectsASecondChargeWithoutCreatingAnotherCompletedSale() {
		SessionPrincipal administrator = administrator("veterinaria-aurora", "AV_DEMO_AURORA_PASSWORD");
		SessionPrincipal cashier = cashier(administrator);
		ServiceChargeCandidate prestation = completedClinicalPrestation(administrator);
		CatalogProduct service = nonStockService(administrator, "B04-RF015-DUP-");
		PosSale first = completedChargedSale(cashier, service, prestation);
		PosSale second = pointOfSaleService.createSale(cashier,
				new PosSaleDraft(UUID.randomUUID(), List.of(new PosSaleLineDraft(service.id(), 1))));

		ApiException duplicate = assertThrows(ApiException.class, () -> pointOfSaleService.confirmSale(cashier, second.id(),
				new PosSaleConfirmationDraft("EFECTIVO", service.salePricePen(),
						List.of(new PosServiceChargeDraft(service.id(), prestation.prestationType(), prestation.prestationId())))));
		assertEquals(HttpStatus.CONFLICT, duplicate.status());
		assertEquals("B04_SERVICE_ALREADY_CHARGED", duplicate.code());
		assertEquals("COMPLETADA", pointOfSaleService.findSale(cashier, first.id()).status());
	}

	@Test
	void keepsAnOrdinaryPhysicalProductSaleIndependentFromServiceCharges() {
		SessionPrincipal administrator = administrator("veterinaria-aurora", "AV_DEMO_AURORA_PASSWORD");
		SessionPrincipal cashier = cashier(administrator);
		String suffix = UUID.randomUUID().toString().substring(0, 8);
		CatalogProduct product = catalogService.createProduct(administrator,
				new CatalogProductDraft(null, "B04-RF015-PROD-" + suffix, "Producto sin prestación " + suffix, null,
						new BigDecimal("8.00"), "ACTIVO_NO_PUBLICADO", true, null));
		stockService.adjust(administrator, product.id(), new StockAdjustmentDraft(1, "Saldo para venta sin prestación", UUID.randomUUID()));

		PosSale sale = pointOfSaleService.createSale(cashier,
				new PosSaleDraft(UUID.randomUUID(), List.of(new PosSaleLineDraft(product.id(), 1))));
		PosSale completed = pointOfSaleService.confirmSale(cashier, sale.id(),
				new PosSaleConfirmationDraft("EFECTIVO", new BigDecimal("8.00")));

		assertEquals("COMPLETADA", completed.status());
		assertTrue(completed.serviceCharges().isEmpty());
		assertEquals(0, stockService.listBalances(administrator).stream()
				.filter(balance -> balance.productId().equals(product.id())).findFirst().orElseThrow().availableQuantity());
	}

	@Test
	void rejectsAnAuroraPrestationFromABosqueSale() {
		SessionPrincipal auroraAdministrator = administrator("veterinaria-aurora", "AV_DEMO_AURORA_PASSWORD");
		ServiceChargeCandidate auroraPrestation = completedClinicalPrestation(auroraAdministrator);
		SessionPrincipal bosqueAdministrator = administrator("veterinaria-bosque", "AV_DEMO_BOSQUE_PASSWORD");
		SessionPrincipal bosqueCashier = cashier(bosqueAdministrator);
		CatalogProduct bosqueService = nonStockService(bosqueAdministrator, "B04-RF015-BOSQUE-");
		PosSale bosqueSale = pointOfSaleService.createSale(bosqueCashier,
				new PosSaleDraft(UUID.randomUUID(), List.of(new PosSaleLineDraft(bosqueService.id(), 1))));

		ApiException crossTenant = assertThrows(ApiException.class, () -> pointOfSaleService.confirmSale(bosqueCashier, bosqueSale.id(),
				new PosSaleConfirmationDraft("EFECTIVO", bosqueService.salePricePen(), List.of(new PosServiceChargeDraft(bosqueService.id(),
						auroraPrestation.prestationType(), auroraPrestation.prestationId())))));
		assertEquals(HttpStatus.NOT_FOUND, crossTenant.status());
		assertEquals("B04_SERVICE_PRESTATION_NOT_FOUND", crossTenant.code());
	}

	@Test
	void marksTheHistoricalLinkAnnulledOnlyThroughTheExistingCommercialCancellation() {
		SessionPrincipal administrator = administrator("veterinaria-aurora", "AV_DEMO_AURORA_PASSWORD");
		SessionPrincipal cashier = cashier(administrator);
		ServiceChargeCandidate prestation = completedClinicalPrestation(administrator);
		CatalogProduct service = nonStockService(administrator, "B04-RF015-CANCEL-");
		PosSale sale = completedChargedSale(cashier, service, prestation);

		pointOfSaleService.cancelSale(administrator, sale.id(),
				new PosSaleCancellationDraft(UUID.randomUUID(), "Corrección comercial sintética de prestación", false));
		PosSale cancelled = pointOfSaleService.findSale(cashier, sale.id());
		assertEquals("ANULADA", cancelled.status());
		assertEquals("ANULADO", cancelled.serviceCharges().getFirst().status());
		assertTrue(pointOfSaleService.listEligibleServiceCharges(cashier).stream()
				.anyMatch(candidate -> candidate.prestationType() == prestation.prestationType()
						&& candidate.prestationId().equals(prestation.prestationId())));
	}

	private PosSale completedChargedSale(SessionPrincipal cashier, CatalogProduct service, ServiceChargeCandidate prestation) {
		PosSale sale = pointOfSaleService.createSale(cashier,
				new PosSaleDraft(UUID.randomUUID(), List.of(new PosSaleLineDraft(service.id(), 1))));
		return pointOfSaleService.confirmSale(cashier, sale.id(), new PosSaleConfirmationDraft("EFECTIVO", service.salePricePen(),
				List.of(new PosServiceChargeDraft(service.id(), prestation.prestationType(), prestation.prestationId()))));
	}

	private ServiceChargeCandidate completedClinicalPrestation(SessionPrincipal administrator) {
		String suffix = UUID.randomUUID().toString().substring(0, 8);
		var owner = ownerPetCareService.createOwner(administrator, new OwnerCommand("Propietaria", "RF015 " + suffix, null, null, null));
		var pet = ownerPetCareService.createPet(administrator, owner.id(),
				new PetCommand("Mascota RF015 " + suffix, "Canino", null, "HEMBRA", null, 24, null));
		SessionPrincipal veterinarian = veterinarian();
		var draft = ownerPetCareService.createClinicalEpisode(veterinarian, pet.id(),
				new ClinicalEpisodeCommand(null, "Prestación facturable", null, "Apta", null, null, null));
		var finalized = ownerPetCareService.finalizeClinicalEpisode(veterinarian, draft.id(),
				new ClinicalEpisodeCommand(null, "Prestación facturable", null, "Apta", null, null, null));
		assertEquals("FINALIZADO", finalized.status());
		return pointOfSaleService.listEligibleServiceCharges(cashier(administrator)).stream()
				.filter(candidate -> candidate.prestationType() == ServicePrestationType.CLINICA && candidate.prestationId().equals(finalized.id()))
				.findFirst().orElseThrow();
	}

	private CatalogProduct nonStockService(SessionPrincipal administrator, String prefix) {
		String suffix = UUID.randomUUID().toString().substring(0, 8);
		return catalogService.createProduct(administrator, new CatalogProductDraft(null, prefix + suffix, "Servicio POS " + suffix,
				null, new BigDecimal("12.00"), "ACTIVO_NO_PUBLICADO", false, null));
	}

	private SessionPrincipal administrator(String tenantSlug, String passwordVariable) {
		String password = required(passwordVariable);
		return new SessionPrincipal(identityAccessService.authenticateStaff(tenantSlug, "administracion@demo.invalid", password).context());
	}

	private SessionPrincipal veterinarian() {
		String email = required("AV_DEMO_VETERINARIO_AURORA_EMAIL");
		String password = required("AV_DEMO_VETERINARIO_AURORA_PASSWORD");
		return new SessionPrincipal(identityAccessService.authenticateStaff("veterinaria-aurora", email, password).context());
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
