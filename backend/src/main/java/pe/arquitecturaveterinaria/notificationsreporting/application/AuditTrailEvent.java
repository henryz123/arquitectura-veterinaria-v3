package pe.arquitecturaveterinaria.notificationsreporting.application;

import java.time.Instant;
import java.util.UUID;

/** Safe read projection of an authoritative event; it never contains free-text reasons or payloads. */
public record AuditTrailEvent(String sourceModule, UUID sourceEventId, String actionCode, String resourceType,
		UUID resourceId, Instant occurredAt, UUID actorPrincipalId, String outcome) {
}
