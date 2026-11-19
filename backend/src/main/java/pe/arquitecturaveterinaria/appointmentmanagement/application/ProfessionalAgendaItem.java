package pe.arquitecturaveterinaria.appointmentmanagement.application;

import java.time.Instant;
import java.util.UUID;

/**
 * Read-only operational projection for the veterinarian assigned to a current
 * clinical reservation. It deliberately excludes owner contacts and clinical
 * narrative; those remain behind the B02 clinical workflow.
 */
public record ProfessionalAgendaItem(UUID reservationId, UUID requestId, String petName, String serviceName,

		Instant startsAt, Instant endsAt, String status) {
}
