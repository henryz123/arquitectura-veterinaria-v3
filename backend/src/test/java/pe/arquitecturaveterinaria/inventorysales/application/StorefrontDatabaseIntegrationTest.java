package pe.arquitecturaveterinaria.inventorysales.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

import pe.arquitecturaveterinaria.identityaccess.application.IdentityAccessService;
import pe.arquitecturaveterinaria.identityaccess.application.B01AdministrationService;
import pe.arquitecturaveterinaria.identityaccess.application.SessionPrincipal;
import pe.arquitecturaveterinaria.shared.api.ApiException;

/**
 * Uses the local synthetic database and rolls fixtures back. It proves that the
 * public projection stays tenant-scoped and leaves balances unchanged on reads.
 */
@SpringBootTest
@Transactional(transactionManager = "jdbcTransactionManager")
class StorefrontDatabaseIntegrationTest {

	@Autowired
	private CatalogService catalogService;

	@Autowired
	private StockService stockService;

	@Autowired
	private StorefrontService storefrontService;

	@Autowired
	private IdentityAccessService identityAccessService;

	@Autowired
	private B01AdministrationService administrationService;

	@Test
	void exposesOnlyPublishedPhysicalProductsForTheResolvedActiveTenantWithoutChangingStock() {
		SessionPrincipal aurora = administrator("veterinaria-aurora", "AV_DEMO_AURORA_PASSWORD");
		SessionPrincipal bosque = administrator("veterinaria-bosque", "AV_DEMO_BOSQUE_PASSWORD");
		String suffix = UUID.randomUUID().toString().substring(0, 8);

		CatalogCategory auroraCategory = catalogService.createCategory(aurora,
				new CatalogCategoryDraft("B05 público " + suffix, "ACTIVA"));
		CatalogProduct availableAurora = catalogService.createProduct(aurora,
				new CatalogProductDraft(auroraCategory.id(), "B05-A-" + suffix, "Alimento Aurora " + suffix,
						"Visible en tienda", new BigDecimal("18.50"), "ACTIVO_PUBLICADO", true, null));
		stockService.adjust(aurora, availableAurora.id(), new StockAdjustmentDraft(3, "Saldo de prueba", UUID.randomUUID()));
		CatalogProduct unavailableAurora = catalogService.createProduct(aurora,
				new CatalogProductDraft(auroraCategory.id(), "B05-Z-" + suffix, "Arena Aurora " + suffix,
						"Sin saldo", new BigDecimal("12.00"), "ACTIVO_PUBLICADO", true, null));
		CatalogProduct privateAurora = catalogService.createProduct(aurora,
				new CatalogProductDraft(auroraCategory.id(), "B05-N-" + suffix, "No publicado " + suffix,
						"No visible", new BigDecimal("11.00"), "ACTIVO_NO_PUBLICADO", true, null));
		CatalogProduct serviceAurora = catalogService.createProduct(aurora,
				new CatalogProductDraft(auroraCategory.id(), "B05-S-" + suffix, "Servicio no físico " + suffix,
						"No vendible en B05-A", new BigDecimal("30.00"), "ACTIVO_PUBLICADO", false, null));

		CatalogCategory bosqueCategory = catalogService.createCategory(bosque,
				new CatalogCategoryDraft("B05 Bosque " + suffix, "ACTIVA"));
		CatalogProduct bosqueProduct = catalogService.createProduct(bosque,
				new CatalogProductDraft(bosqueCategory.id(), "B05-B-" + suffix, "Alimento Bosque " + suffix,
						"Aislado", new BigDecimal("20.00"), "ACTIVO_PUBLICADO", true, null));
		stockService.adjust(bosque, bosqueProduct.id(), new StockAdjustmentDraft(2, "Saldo de prueba", UUID.randomUUID()));

		StorefrontCatalog auroraCatalog = storefrontService.catalogFor("veterinaria-aurora");
		assertEquals("veterinaria-aurora", auroraCatalog.tenantSlug());
		assertTrue(auroraCatalog.products().stream().anyMatch(item -> item.id().equals(availableAurora.id()) && item.available()));
		assertTrue(auroraCatalog.products().stream().anyMatch(item -> item.id().equals(unavailableAurora.id()) && !item.available()));
		assertFalse(auroraCatalog.products().stream().anyMatch(item -> item.id().equals(privateAurora.id())));
		assertFalse(auroraCatalog.products().stream().anyMatch(item -> item.id().equals(serviceAurora.id())));
		assertFalse(auroraCatalog.products().stream().anyMatch(item -> item.id().equals(bosqueProduct.id())));

		StorefrontCatalog bosqueCatalog = storefrontService.catalogFor("veterinaria-bosque");
		assertTrue(bosqueCatalog.products().stream().anyMatch(item -> item.id().equals(bosqueProduct.id()) && item.available()));
		assertFalse(bosqueCatalog.products().stream().anyMatch(item -> item.id().equals(availableAurora.id())));

		ApiException unavailable = assertThrows(ApiException.class, () -> storefrontService.catalogFor("organizacion-inexistente"));
		assertEquals(HttpStatus.NOT_FOUND, unavailable.status());
		assertEquals("B05_STOREFRONT_NOT_AVAILABLE", unavailable.code());

		assertNotNull(stockService.listBalances(aurora).stream().filter(item -> item.productId().equals(availableAurora.id())).findFirst()
				.orElseThrow());
		assertEquals(3, stockService.listBalances(aurora).stream().filter(item -> item.productId().equals(availableAurora.id())).findFirst()
				.orElseThrow().availableQuantity());
	}

	@Test
	void rejectsAStorefrontWhenTheTenantIsSuspendedByTheApprovedSaasFlow() {
		String globalEmail = System.getenv("AV_DEMO_SAAS_EMAIL");
		String globalPassword = System.getenv("AV_DEMO_SAAS_PASSWORD");
		Assumptions.assumeTrue(globalEmail != null && !globalEmail.isBlank()
				&& globalPassword != null && !globalPassword.isBlank(),
				"Requiere credenciales SaaS sintéticas locales.");
		SessionPrincipal globalAdministrator = new SessionPrincipal(identityAccessService.authenticateSaas(globalEmail, globalPassword).context());
		var aurora = administrationService.listOrganizations(globalAdministrator).stream()
				.filter(item -> item.commercialName().startsWith("Veterinaria Aurora")).findFirst().orElseThrow();

		administrationService.suspendTenant(globalAdministrator, aurora.id(), "Prueba B05-A de tienda suspendida", true);
		ApiException unavailable = assertThrows(ApiException.class, () -> storefrontService.catalogFor("veterinaria-aurora"));
		assertEquals(HttpStatus.NOT_FOUND, unavailable.status());
		assertEquals("B05_STOREFRONT_NOT_AVAILABLE", unavailable.code());
	}

	private SessionPrincipal administrator(String tenantSlug, String passwordVariable) {
		String password = System.getenv(passwordVariable);
		assertNotNull(password, "La prueba requiere credenciales sintéticas locales.");
		return new SessionPrincipal(identityAccessService.authenticateStaff(tenantSlug, "administracion@demo.invalid", password).context());
	}
}
