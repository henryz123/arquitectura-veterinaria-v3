package pe.arquitecturaveterinaria.inventorysales.api;

import java.time.LocalDate;

import jakarta.validation.constraints.NotNull;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import pe.arquitecturaveterinaria.identityaccess.application.SessionPrincipal;
import pe.arquitecturaveterinaria.inventorysales.application.CommercialReport;
import pe.arquitecturaveterinaria.inventorysales.application.CommercialReportService;
import pe.arquitecturaveterinaria.shared.api.ApiEnvelope;

/** B04 commercial snapshot for tenant administration; all aggregates remain scope-derived. */
@RestController
@RequestMapping("/api/v1/inventory/reports")
@Validated
public class CommercialReportController {

	private final CommercialReportService service;

	public CommercialReportController(CommercialReportService service) {
		this.service = service;
	}

	@GetMapping("/commercial")
	ApiEnvelope<CommercialReport> find(@AuthenticationPrincipal SessionPrincipal principal,
			@RequestParam @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
		return ApiEnvelope.of(service.find(principal, from, to));
	}
}
