package pe.arquitecturaveterinaria.inventorysales.application;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import pe.arquitecturaveterinaria.identityaccess.application.SessionPrincipal;
import pe.arquitecturaveterinaria.identityaccess.infrastructure.TenantContextJdbc;
import pe.arquitecturaveterinaria.inventorysales.infrastructure.CommercialReportJdbcRepository;
import pe.arquitecturaveterinaria.shared.api.ApiException;

/** Read-only B04 reporting facade. Database RLS receives its tenant scope from the authenticated session only. */
@Service
public class CommercialReportService {

	private final CommercialReportJdbcRepository repository;
	private final TenantContextJdbc tenantContext;
	private final InventoryAccessPolicy accessPolicy = new InventoryAccessPolicy();

	public CommercialReportService(CommercialReportJdbcRepository repository, TenantContextJdbc tenantContext) {
		this.repository = repository;
		this.tenantContext = tenantContext;
	}

	@Transactional(transactionManager = "jdbcTransactionManager", readOnly = true)
	public CommercialReport find(SessionPrincipal actor, LocalDate from, LocalDate to) {
		accessPolicy.requireCommercialReport(actor);
		Period period = period(from, to);
		tenantContext.setTenantId(actor.session().tenantId());
		return repository.find(period.from(), period.to());
	}

	private Period period(LocalDate from, LocalDate to) {
		if (from == null || to == null) {
			throw validation("period", "Indica la fecha inicial y final del informe.");
		}
		if (to.isBefore(from)) {
			throw validation("period", "La fecha final no puede ser anterior a la fecha inicial.");
		}
		if (ChronoUnit.DAYS.between(from, to) > 366) {
			throw validation("period", "El período del informe no puede superar 366 días.");
		}
		return new Period(from, to);
	}

	private ApiException validation(String field, String message) {
		return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "B04_VALIDATION_" + field.toUpperCase(), message);
	}

	private record Period(LocalDate from, LocalDate to) {
	}
}
