package pe.arquitecturaveterinaria.inventorysales.application;

import java.time.Instant;
import java.util.UUID;

/** Auditable POS link to one completed clinical or grooming prestation. */
public record PosServiceCharge(UUID id, UUID saleLineId, ServicePrestationType prestationType, UUID prestationId,
		String petName, Instant performedAt, String status, Instant chargedAt, Instant cancelledAt) {
}
