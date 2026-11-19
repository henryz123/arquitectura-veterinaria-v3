package pe.arquitecturaveterinaria.notificationsreporting.api;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import pe.arquitecturaveterinaria.identityaccess.application.SessionPrincipal;
import pe.arquitecturaveterinaria.notificationsreporting.application.AuditTrailPage;
import pe.arquitecturaveterinaria.notificationsreporting.application.AuditTrailService;
import pe.arquitecturaveterinaria.shared.api.ApiEnvelope;

@RestController
@RequestMapping("/api/v1/audit-trail")
@Validated
public class AuditTrailController {
	private final AuditTrailService service;

	public AuditTrailController(AuditTrailService service) {
		this.service = service;
	}

	@GetMapping
	ApiEnvelope<AuditTrailPage> list(@AuthenticationPrincipal SessionPrincipal principal,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
			@RequestParam(required = false) String module, @RequestParam(required = false) String action,
			@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
		return ApiEnvelope.of(service.list(principal, from, to, module, action, page, size));
	}
}
