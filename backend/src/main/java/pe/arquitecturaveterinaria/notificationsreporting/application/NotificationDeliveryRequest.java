package pe.arquitecturaveterinaria.notificationsreporting.application;

import java.util.UUID;

/** Internal command emitted with a committed business event, never a public request DTO. */
public record NotificationDeliveryRequest(UUID tenantId, UUID recipientPrincipalId, String sourceModule, UUID sourceEventId,
		String sourceResourceType, UUID sourceResourceId, String notificationCode) {
}
