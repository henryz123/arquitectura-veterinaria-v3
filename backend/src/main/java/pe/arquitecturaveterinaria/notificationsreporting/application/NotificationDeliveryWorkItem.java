package pe.arquitecturaveterinaria.notificationsreporting.application;

import java.util.UUID;

/** Locked delivery item. It intentionally omits all user-generated text and credentials. */
public record NotificationDeliveryWorkItem(UUID id, String sourceModule, UUID sourceEventId, String sourceResourceType,
		UUID sourceResourceId, String notificationCode) {
}
