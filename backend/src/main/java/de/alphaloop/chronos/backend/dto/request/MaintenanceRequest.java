package de.alphaloop.chronos.backend.dto.request;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/**
 * Request for scheduling a maintenance window.
 * Used for: POST /api/equipment/{id}/maintenance
 */
public record MaintenanceRequest(

        @NotNull(message = "Maintenance start date is required")
        LocalDate startDate,

        @NotNull(message = "Maintenance end date is required")
        LocalDate endDate
) {
}