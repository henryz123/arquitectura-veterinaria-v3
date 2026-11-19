package pe.arquitecturaveterinaria.inventorysales.api;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import pe.arquitecturaveterinaria.inventorysales.application.StorefrontCatalog;
import pe.arquitecturaveterinaria.inventorysales.application.StorefrontService;
import pe.arquitecturaveterinaria.shared.api.ApiEnvelope;

/** Anonymous, read-only B05-A storefront endpoint. Protected catalog endpoints remain separate. */
@RestController
@RequestMapping("/api/v1/tienda")
@Validated
public class StorefrontController {

	private final StorefrontService service;

	public StorefrontController(StorefrontService service) {
		this.service = service;
	}

	@GetMapping("/{tenantSlug}/productos")
	ApiEnvelope<StorefrontCatalog> listPublishedProducts(
			@PathVariable @Size(max = 80) @Pattern(regexp = "[a-z0-9]+(?:-[a-z0-9]+)*") String tenantSlug) {
		return ApiEnvelope.of(service.catalogFor(tenantSlug));
	}
}
