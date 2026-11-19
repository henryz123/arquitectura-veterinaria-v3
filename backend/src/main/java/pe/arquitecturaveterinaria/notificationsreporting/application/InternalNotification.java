package pe.arquitecturaveterinaria.notificationsreporting.application;

import java.time.Instant;
import java.util.UUID;

/** Tenant-recipient scoped in-account notification. UI text is derived from its safe code. */
public record InternalNotification(UUID id, String sourceModule, UUID sourceEventId, String sourceResourceType,
		UUID sourceResourceId, String notificationCode, Instant acceptedLocalAt, Instant readAt, Instant createdAt) {
}
