package pe.arquitecturaveterinaria.inventorysales.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Tenant-local B04 snapshot. POS revenue, returns and current stock remain separate facts. */
public record CommercialReport(LocalDate from, LocalDate to, long posSalesCount, BigDecimal posSalesGrossAmountPen,
		long onlineConfirmedOrdersCount, BigDecimal onlineConfirmedOrdersGrossAmountPen,
		long onlineValidatedPaymentsCount, BigDecimal onlineValidatedPaymentsAmountPen,
		long cancellationsCount, long returnRequestsCount, long returnsPendingInspectionCount, long returnsClosedCount,
		long inventoryProductsCount, long availableStockUnits, long outOfStockProductsCount, long reconciliationMismatchesCount,
		List<CommercialReportProduct> topProducts, List<CommercialReportMovement> movements) {
}
