package de.alphaloop.chronos.backend.dto.response;

import de.alphaloop.chronos.backend.enums.ProjectStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Lean project response.
 * Note: customerName is a DENORMALIZED field — it comes from project.customer.name,
 * not from the project itself. The mapper handles this traversal.
 * <p>
 * WHY include customerName here instead of a nested CustomerResponse?
 * For a project list, you only need the customer's name (for display).
 * A full CustomerResponse would include email, phone, createdAt — wasted bytes.
 * Flat denormalization is cleaner for list views.
 */
public record ProjectResponse(
        Long id,
        String name,
        ProjectStatus status,
        LocalDate startDate,
        LocalDate endDate,
        Long customerId,
        String customerName,  // denormalized from project.customer.name
        LocalDateTime createdAt
) {}