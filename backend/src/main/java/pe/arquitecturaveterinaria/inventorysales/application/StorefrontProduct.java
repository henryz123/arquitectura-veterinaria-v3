package pe.arquitecturaveterinaria.inventorysales.application;

import java.math.BigDecimal;
import java.util.UUID;

/** Commercial information that a public visitor may see for one active tenant. */
public record StorefrontProduct(
		UUID id,
		String categoryName,
		String title,
		String description,
		BigDecimal salePricePen,
		String imageUrl,
		boolean available) {
}
