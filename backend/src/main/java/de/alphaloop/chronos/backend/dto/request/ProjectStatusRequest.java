package de.alphaloop.chronos.backend.dto.request;

import de.alphaloop.chronos.backend.enums.ProjectStatus;
import jakarta.validation.constraints.NotNull;

/**
 * Request for explicit status transition.
 * Sent as: PATCH /api/projects/{id}/status  with body: {"status": "ACTIVE"}
 *
 * WHY a dedicated DTO for status change?
 * Status transitions have business rules (canTransitionTo()).
 * Keeping it separate from ProjectUpdateRequest makes the API contract clear:
 * "this endpoint is specifically for changing status, not general data updates".
 */
public record ProjectStatusRequest(

        @NotNull(message = "Target status is required")
        ProjectStatus status

) {}