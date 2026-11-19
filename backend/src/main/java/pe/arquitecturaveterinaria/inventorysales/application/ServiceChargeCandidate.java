package pe.arquitecturaveterinaria.inventorysales.application;

import java.time.Instant;
import java.util.UUID;

/** Minimal commercial projection; it deliberately omits clinical narrative and diagnosis. */
public record ServiceChargeCandidate(ServicePrestationType prestationType, UUID prestationId, String petName, Instant performedAt) {
}
