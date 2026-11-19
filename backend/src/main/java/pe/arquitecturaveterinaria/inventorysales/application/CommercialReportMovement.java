package pe.arquitecturaveterinaria.inventorysales.application;

/** Immutable ledger aggregation for a selected commercial reporting period. */
public record CommercialReportMovement(String movementType, long entries, long quantityDelta) {
}
