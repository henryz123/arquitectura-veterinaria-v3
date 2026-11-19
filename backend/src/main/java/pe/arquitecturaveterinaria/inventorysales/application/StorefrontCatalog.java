package pe.arquitecturaveterinaria.inventorysales.application;

import java.util.List;

/** Public B05-A projection: no tenant administration or stock ledger details. */
public record StorefrontCatalog(String tenantSlug, List<StorefrontProduct> products) {
}
