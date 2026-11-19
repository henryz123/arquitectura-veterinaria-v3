package pe.arquitecturaveterinaria.notificationsreporting.application;

import org.springframework.stereotype.Component;

/** The durable database record is the V1 in-account delivery channel. */
@Component
public class DirectInAppNotificationDelivery implements InAppNotificationDeliveryPort {
	@Override
	public void accept(NotificationDeliveryWorkItem item) {
		// Delivery is made visible by the protected status transition in the repository.
	}
}
