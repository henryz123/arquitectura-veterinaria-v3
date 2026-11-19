package pe.arquitecturaveterinaria.notificationsreporting.api;

import java.util.List;
import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import pe.arquitecturaveterinaria.identityaccess.application.SessionPrincipal;
import pe.arquitecturaveterinaria.notificationsreporting.application.InternalNotification;
import pe.arquitecturaveterinaria.notificationsreporting.application.InternalNotificationService;
import pe.arquitecturaveterinaria.shared.api.ApiEnvelope;

@RestController
@RequestMapping("/api/v1/notifications")
public class InternalNotificationController {
	private final InternalNotificationService service;

	public InternalNotificationController(InternalNotificationService service) {
		this.service = service;
	}

	@GetMapping
	ApiEnvelope<List<InternalNotification>> listOwn(@AuthenticationPrincipal SessionPrincipal principal) {
		return ApiEnvelope.of(service.listOwn(principal));
	}

	@PatchMapping("/{notificationId}/read")
	ApiEnvelope<InternalNotification> markRead(@AuthenticationPrincipal SessionPrincipal principal,
			@PathVariable UUID notificationId) {
		return ApiEnvelope.of(service.markRead(principal, notificationId));
	}
}
