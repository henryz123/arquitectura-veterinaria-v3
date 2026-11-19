package pe.arquitecturaveterinaria.notificationsreporting.application;

import org.springframework.http.HttpStatus;

import pe.arquitecturaveterinaria.identityaccess.application.SessionPrincipal;
import pe.arquitecturaveterinaria.identityaccess.domain.AccountType;
import pe.arquitecturaveterinaria.shared.api.ApiException;

/** B06 checks are server-side; a route or payload never supplies a tenant or recipient scope. */
final class NotificationsReportingAccessPolicy {

	void requireAuditAdministrator(SessionPrincipal actor) {
		requireActiveTenantMember(actor);
		if (actor.accountType() != AccountType.STAFF || !actor.session().roles().contains("ADMIN_VETERINARIA")) {
			throw new ApiException(HttpStatus.FORBIDDEN, "B06_AUDIT_ADMIN_REQUIRED",
					"La auditoría transversal requiere administración vigente de la veterinaria.");
		}
	}

	void requireNotificationRecipient(SessionPrincipal actor) {
		requireActiveTenantMember(actor);
		if (actor.accountType() != AccountType.STAFF && actor.accountType() != AccountType.CLIENT) {
			throw new ApiException(HttpStatus.FORBIDDEN, "B06_NOTIFICATION_ACCOUNT_REQUIRED",
					"La bandeja interna requiere una cuenta activa de la veterinaria.");
		}
	}

	private void requireActiveTenantMember(SessionPrincipal actor) {
		if (actor == null || actor.session().tenantId() == null || !"ACTIVO".equals(actor.session().tenantStatus())) {
			throw new ApiException(HttpStatus.FORBIDDEN, "TENANT_NOT_ACTIVE",
					"La veterinaria no está disponible para esta operación.");
		}
	}
}
