package pe.arquitecturaveterinaria.inventorysales.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import pe.arquitecturaveterinaria.identityaccess.application.IdentityAccessService;
import pe.arquitecturaveterinaria.identityaccess.application.SessionPrincipal;
import pe.arquitecturaveterinaria.identityaccess.application.TenantSession;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.DeliveryMode;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.Draft;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.LineDraft;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.ManualPaymentDecision;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.ManualPaymentDraft;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.ManualPaymentStatus;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.Order;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.StaffOrder;

/** B04 report regression: values are tenant-local and never merge commercial facts. */
@SpringBootTest
@Transactional(transactionManager = "jdbcTransactionManager")
class CommercialReportDatabaseIntegrationTest {

	private static final ZoneId LIMA = ZoneId.of("America/Lima");

	@Autowired private CatalogService catalogService;
	@Autowired private StockService stockService;
	@Autowired private PointOfSaleService pointOfSaleService;
	@Autowired private OnlineOrderService onlineOrderService;
	@Autowired private CommercialReportService commercialReportService;
	@Autowired private IdentityAccessService identityAccessService;

	@Test
	void separatesPosSalesCancellationsAndPendingReturnsWithoutCrossTenantData() {
		SessionPrincipal administrator = administrator();
		SessionPrincipal cashier = cashier(administrator);
		LocalDate today = LocalDate.now(LIMA);
		CommercialReport baseline = commercialReportService.find(administrator, today, today);
		CatalogProduct product = inventoryProduct(administrator);
		stockService.adjust(administrator, product.id(), new StockAdjustmentDraft(3, "Saldo para informe", UUID.randomUUID()));

		PosSale completed = completedSale(cashier, product.id(), 2, new BigDecimal("10.00"));
		PosSaleLine line = completed.lines().getFirst();
		pointOfSaleService.requestReturn(cashier, completed.id(), new PosReturnDraft(UUID.randomUUID(), "Devolución pendiente",
				List.of(new PosReturnLineDraft(line.id(), 1))));

		PosSale cancelled = completedSale(cashier, product.id(), 1, new BigDecimal("5.00"));
		pointOfSaleService.cancelSale(administrator, cancelled.id(),
				new PosSaleCancellationDraft(UUID.randomUUID(), "Venta sin salida física", false));

		CommercialReport report = commercialReportService.find(administrator, today, today);

		assertEquals(baseline.posSalesCount() + 1, report.posSalesCount());
		assertEquals(baseline.posSalesGrossAmountPen().add(new BigDecimal("10.00")), report.posSalesGrossAmountPen());
		assertEquals(baseline.cancellationsCount() + 1, report.cancellationsCount());
		assertEquals(baseline.returnRequestsCount() + 1, report.returnRequestsCount());
		assertEquals(baseline.returnsPendingInspectionCount() + 1, report.returnsPendingInspectionCount());
		assertEquals(baseline.inventoryProductsCount() + 1, report.inventoryProductsCount());
		assertEquals(baseline.availableStockUnits() + 1, report.availableStockUnits());
		assertEquals(baseline.reconciliationMismatchesCount(), report.reconciliationMismatchesCount());
		CommercialReportProduct productRow = report.topProducts().stream()
				.filter(row -> row.productId().equals(product.id())).findFirst().orElseThrow();
		assertEquals(2, productRow.quantitySold());
		assertEquals(new BigDecimal("10.00"), productRow.grossAmountPen());

		CommercialReport isolated = commercialReportService.find(foreignTenant(administrator), today, today);
		assertEquals(0, isolated.posSalesCount());
		assertEquals(0, isolated.inventoryProductsCount());
	}

	@Test
	void reportsConfirmedOnlineOrdersAndValidatedPaymentsAsSeparateTenantLocalFacts() {
		SessionPrincipal administrator = administrator();
		SessionPrincipal client = client();
		LocalDate today = LocalDate.now(LIMA);
		CommercialReport baseline = commercialReportService.find(administrator, today, today);
		CatalogProduct product = publishedInventoryProduct(administrator);
		stockService.adjust(administrator, product.id(), new StockAdjustmentDraft(2, "Saldo para pedido web del informe", UUID.randomUUID()));

		Order order = onlineOrderService.create(client, new Draft(UUID.randomUUID(), DeliveryMode.RECOJO, "999 000 111", null, null,
				List.of(new LineDraft(product.id(), 1, product.salePricePen()))));
		assertEquals("CONFIRMADO", onlineOrderService.confirm(administrator, order.id()).status());
		StaffOrder declared = onlineOrderService.recordManualPayment(administrator, order.id(),
				new ManualPaymentDraft(UUID.randomUUID(), "EFECTIVO", product.salePricePen(), null));
		onlineOrderService.decideManualPayment(administrator, order.id(), declared.payments().getFirst().id(),
				new ManualPaymentDecision(UUID.randomUUID(), ManualPaymentStatus.VALIDADO, null));

		CommercialReport report = commercialReportService.find(administrator, today, today);

		assertEquals(baseline.posSalesCount(), report.posSalesCount());
		assertEquals(baseline.posSalesGrossAmountPen(), report.posSalesGrossAmountPen());
		assertEquals(baseline.onlineConfirmedOrdersCount() + 1, report.onlineConfirmedOrdersCount());
		assertEquals(baseline.onlineConfirmedOrdersGrossAmountPen().add(product.salePricePen()), report.onlineConfirmedOrdersGrossAmountPen());
		assertEquals(baseline.onlineValidatedPaymentsCount() + 1, report.onlineValidatedPaymentsCount());
		assertEquals(baseline.onlineValidatedPaymentsAmountPen().add(product.salePricePen()), report.onlineValidatedPaymentsAmountPen());

		CommercialReport isolated = commercialReportService.find(foreignTenant(administrator), today, today);
		assertEquals(0, isolated.onlineConfirmedOrdersCount());
		assertEquals(0, isolated.onlineValidatedPaymentsCount());
	}

	private CatalogProduct inventoryProduct(SessionPrincipal administrator) {
		String suffix = UUID.randomUUID().toString().substring(0, 8);
		return catalogService.createProduct(administrator,
				new CatalogProductDraft(null, "B04-REPORT-" + suffix, "Producto informe " + suffix, null, new BigDecimal("5.00"),
						"ACTIVO_NO_PUBLICADO", true, null));
	}

	private CatalogProduct publishedInventoryProduct(SessionPrincipal administrator) {
		String suffix = UUID.randomUUID().toString().substring(0, 8);
		return catalogService.createProduct(administrator,
				new CatalogProductDraft(null, "B06-A-REPORT-" + suffix, "Producto web informe " + suffix, null,
						new BigDecimal("5.00"), "ACTIVO_PUBLICADO", true, null));
	}

	private PosSale completedSale(SessionPrincipal cashier, UUID productId, int quantity, BigDecimal total) {
		PosSale sale = pointOfSaleService.createSale(cashier,
				new PosSaleDraft(UUID.randomUUID(), List.of(new PosSaleLineDraft(productId, quantity))));
		return pointOfSaleService.confirmSale(cashier, sale.id(), new PosSaleConfirmationDraft("EFECTIVO", total));
	}

	private SessionPrincipal administrator() {
		String password = System.getenv("AV_DEMO_AURORA_PASSWORD");
		assertNotNull(password, "La prueba requiere AV_DEMO_AURORA_PASSWORD local.");
		return new SessionPrincipal(identityAccessService.authenticateStaff("veterinaria-aurora", "administracion@demo.invalid", password).context());
	}

	private SessionPrincipal client() {
		String email = System.getenv("AV_DEMO_CLIENT_AURORA_EMAIL");
		String password = System.getenv("AV_DEMO_CLIENT_AURORA_PASSWORD");
		assertNotNull(email, "La prueba requiere AV_DEMO_CLIENT_AURORA_EMAIL local.");
		assertNotNull(password, "La prueba requiere AV_DEMO_CLIENT_AURORA_PASSWORD local.");
		return new SessionPrincipal(identityAccessService.authenticateClient("veterinaria-aurora", email, password).context());
	}

	private SessionPrincipal cashier(SessionPrincipal source) {
		TenantSession session = source.session();
		return new SessionPrincipal(new TenantSession(session.sessionId(), session.accountId(), session.tenantId(), session.tenantSlug(),
				session.tenantCommercialName(), session.tenantStatus(), session.accountType(), List.of("CAJERO"),
				session.sessionTokenHash(), Instant.now().plusSeconds(3600)));
	}

	private SessionPrincipal foreignTenant(SessionPrincipal source) {
		TenantSession session = source.session();
		return new SessionPrincipal(new TenantSession(session.sessionId(), session.accountId(), UUID.randomUUID(), "otro-tenant",
				"Otro tenant", session.tenantStatus(), session.accountType(), List.of("ADMIN_VETERINARIA"),
				session.sessionTokenHash(), Instant.now().plusSeconds(3600)));
	}
}
