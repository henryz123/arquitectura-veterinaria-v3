package pe.arquitecturaveterinaria.inventorysales.application;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import pe.arquitecturaveterinaria.identityaccess.infrastructure.TenantContextJdbc;
import pe.arquitecturaveterinaria.inventorysales.infrastructure.StorefrontJdbcRepository;
import pe.arquitecturaveterinaria.shared.api.ApiException;

/** Public B05-A catalogue. It never creates a cart, order, payment, or stock movement. */
@Service
public class StorefrontService {

	private final StorefrontJdbcRepository repository;
	private final TenantContextJdbc tenantContext;

	public StorefrontService(StorefrontJdbcRepository repository, TenantContextJdbc tenantContext) {
		this.repository = repository;
		this.tenantContext = tenantContext;
	}

	@Transactional(transactionManager = "jdbcTransactionManager", readOnly = true)
	public StorefrontCatalog catalogFor(String tenantSlug) {
		String normalizedSlug = normalizedSlug(tenantSlug);
		StorefrontJdbcRepository.ActiveTenant tenant = repository.findActiveTenant(normalizedSlug)
				.orElseThrow(this::storefrontUnavailable);
		tenantContext.setTenantId(tenant.id());
		List<StorefrontProduct> products = repository.listPublishedPhysicalProducts();
		return new StorefrontCatalog(tenant.slug(), products);
	}

	private String normalizedSlug(String tenantSlug) {
		if (tenantSlug == null || !tenantSlug.matches("[a-z0-9]+(?:-[a-z0-9]+)*") || tenantSlug.length() > 80) {
			throw storefrontUnavailable();
		}
		return tenantSlug;
	}

	private ApiException storefrontUnavailable() {
		return new ApiException(HttpStatus.NOT_FOUND, "B05_STOREFRONT_NOT_AVAILABLE",
				"La tienda solicitada no está disponible.");
	}
}
