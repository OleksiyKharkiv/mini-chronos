package de.alphaloop.chronos.backend.dto.response;

import de.alphaloop.chronos.backend.enums.ProjectStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Rich project response for the detail page.
 * Includes the order list — loaded via EntityGraph.
 */
public record ProjectDetailResponse(
        Long id,
        String name,
        ProjectStatus status,
        LocalDate startDate,
        LocalDate endDate,
        String description,
        Long customerId,
        String customerName,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        List<OrderResponse> orders
) {}