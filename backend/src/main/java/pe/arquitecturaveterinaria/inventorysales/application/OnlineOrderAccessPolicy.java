package pe.arquitecturaveterinaria.inventorysales.application;

import org.springframework.http.HttpStatus;

import pe.arquitecturaveterinaria.identityaccess.application.SessionPrincipal;
import pe.arquitecturaveterinaria.identityaccess.domain.AccountType;
import pe.arquitecturaveterinaria.shared.api.ApiException;

/** Authorization for B05-B is always derived from the resolved session. */
public final class OnlineOrderAccessPolicy {
	public void requireClient(SessionPrincipal actor) {
		if (actor == null || actor.accountType() != AccountType.CLIENT || actor.session().tenantId() == null) {
			throw new ApiException(HttpStatus.FORBIDDEN, "B05_CLIENT_REQUIRED", "La operación requiere una cuenta cliente activa.");
		}
		requireActiveTenant(actor);
	}
	public void requireDeliveryAdministrator(SessionPrincipal actor) {
		if (actor == null || actor.accountType() != AccountType.STAFF || actor.session().tenantId() == null
				|| !actor.session().roles().contains("ADMIN_VETERINARIA")) {
			throw new ApiException(HttpStatus.FORBIDDEN, "B05_DELIVERY_ADMIN_REQUIRED",
					"Se requiere administración de veterinaria para configurar delivery.");
		}
		requireActiveTenant(actor);
	}
	/** B05-C1 commercial confirmation is limited to the tenant's cashier or administrator. */
	public void requireCommercialOperator(SessionPrincipal actor) {
		if (actor == null || actor.accountType() != AccountType.STAFF || actor.session().tenantId() == null
				|| (actor.session().roles().stream().noneMatch(role -> "ADMIN_VETERINARIA".equals(role) || "CAJERO".equals(role)))) {
			throw new ApiException(HttpStatus.FORBIDDEN, "B05_COMMERCIAL_OPERATOR_REQUIRED",
					"La confirmación de pedidos requiere CAJERO o administración de veterinaria vigente.");
		}
		requireActiveTenant(actor);
	}
	/** A negotiated price outside the current catalogue amount is an administrative commercial decision. */
	public void requirePriceProposalAdministrator(SessionPrincipal actor) {
		if (actor == null || actor.accountType() != AccountType.STAFF || actor.session().tenantId() == null
				|| !actor.session().roles().contains("ADMIN_VETERINARIA")) {
			throw new ApiException(HttpStatus.FORBIDDEN, "B05_PRICE_PROPOSAL_ADMIN_REQUIRED",
					"Solo administración de veterinaria puede proponer un precio distinto al catálogo vigente.");
		}
		requireActiveTenant(actor);
	}
	private void requireActiveTenant(SessionPrincipal actor) {
		if (!"ACTIVO".equals(actor.session().tenantStatus())) {
			throw new ApiException(HttpStatus.FORBIDDEN, "TENANT_NOT_ACTIVE", "La veterinaria no está disponible para esta operación.");
		}
	}
}
