package pe.arquitecturaveterinaria.notificationsreporting.application;

import java.util.List;

/** Bounded, deterministic audit page. */
public record AuditTrailPage(List<AuditTrailEvent> events, boolean hasMore) {
}
