package pe.arquitecturaveterinaria.inventorysales.application;

import java.math.BigDecimal;
import java.util.UUID;

/** Gross POS product ranking for a selected period; returns are intentionally reported separately. */
public record CommercialReportProduct(UUID productId, String sku, String title, long quantitySold, BigDecimal grossAmountPen) {
}
