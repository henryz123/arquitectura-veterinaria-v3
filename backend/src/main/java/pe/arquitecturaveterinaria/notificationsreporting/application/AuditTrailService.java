package pe.arquitecturaveterinaria.notificationsreporting.application;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import pe.arquitecturaveterinaria.identityaccess.application.SessionPrincipal;
import pe.arquitecturaveterinaria.identityaccess.infrastructure.TenantContextJdbc;
import pe.arquitecturaveterinaria.notificationsreporting.infrastructure.NotificationsReportingJdbcRepository;
import pe.arquitecturaveterinaria.shared.api.ApiException;

/** Read-only B06 projection over the original module events. */
@Service
public class AuditTrailService {
	private static final ZoneId LIMA = ZoneId.of("America/Lima");
	private static final Set<String> MODULES = Set.of("ALL", "B01", "B02", "B03", "B04", "B05");
	private final NotificationsReportingJdbcRepository repository;
	private final TenantContextJdbc tenantContext;
	private final NotificationsReportingAccessPolicy accessPolicy = new NotificationsReportingAccessPolicy();

	public AuditTrailService(NotificationsReportingJdbcRepository repository, TenantContextJdbc tenantContext) {
		this.repository = repository;
		this.tenantContext = tenantContext;
	}

	@Transactional(transactionManager = "jdbcTransactionManager", readOnly = true)
	public AuditTrailPage list(SessionPrincipal actor, LocalDate from, LocalDate to, String sourceModule, String actionCode,
			Integer page, Integer size) {
		accessPolicy.requireAuditAdministrator(actor);
		Period period = period(from, to);
		String module = module(sourceModule);
		String action = action(actionCode);
		int safePage = bounded(page, 0, 10_000, "page");
		int safeSize = bounded(size, 1, 50, "size");
		tenantContext.setTenantId(actor.session().tenantId());
		List<AuditTrailEvent> rows = repository.listAuditEvents(period.from(), period.to(), module, action, safePage * safeSize,
				safeSize + 1);
		boolean hasMore = rows.size() > safeSize;
		return new AuditTrailPage(hasMore ? rows.subList(0, safeSize) : rows, hasMore);
	}

	private Period period(LocalDate from, LocalDate to) {
		LocalDate end = to == null ? LocalDate.now(LIMA) : to;
		LocalDate start = from == null ? end.minusDays(29) : from;
		if (end.isBefore(start) || ChronoUnit.DAYS.between(start, end) > 366) {
			throw validation("period", "El período de auditoría debe estar ordenado y no superar 366 días.");
		}
		return new Period(start, end);
	}

	private String module(String raw) {
		String value = raw == null || raw.isBlank() ? "ALL" : raw.trim().toUpperCase();
		if (!MODULES.contains(value)) {
			throw validation("module", "El módulo de auditoría solicitado no es válido.");
		}
		return value;
	}

	private String action(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		String value = raw.trim().toUpperCase();
		if (!value.matches("[A-Z][A-Z0-9_]{2,63}")) {
			throw validation("action", "El tipo de operación solicitado no es válido.");
		}
		return value;
	}

	private int bounded(Integer value, int minimum, int maximum, String field) {
		int result = value == null ? minimum : value;
		if (result < minimum || result > maximum) {
			throw validation(field, "El valor de paginación no es válido.");
		}
		return result;
	}

	private ApiException validation(String field, String message) {
		return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "B06_AUDIT_VALIDATION_" + field.toUpperCase(), message);
	}

	private record Period(LocalDate from, LocalDate to) {
	}
}
