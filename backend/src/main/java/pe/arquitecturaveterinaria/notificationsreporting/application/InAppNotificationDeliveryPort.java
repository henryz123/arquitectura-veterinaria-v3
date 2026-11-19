package pe.arquitecturaveterinaria.notificationsreporting.application;

/**
 * Boundary for post-business in-account delivery. The V1 implementation has no
 * external provider; a controlled failure can be exercised without rolling back
 * the source business transaction.
 */
@FunctionalInterface
public interface InAppNotificationDeliveryPort {
	void accept(NotificationDeliveryWorkItem item);
}
