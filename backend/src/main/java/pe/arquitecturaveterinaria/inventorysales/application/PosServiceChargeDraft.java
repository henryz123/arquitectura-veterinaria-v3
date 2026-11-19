package pe.arquitecturaveterinaria.inventorysales.application;

import java.util.UUID;

/** Requested optional link between a non-stock POS product and a completed prestation. */
public record PosServiceChargeDraft(UUID productId, ServicePrestationType prestationType, UUID prestationId) {
}
