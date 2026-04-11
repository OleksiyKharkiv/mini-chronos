package de.alphaloop.chronos.backend.dto.response;

import de.alphaloop.chronos.backend.enums.OrderStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Lean order response for lists (project detail page order table, order list).
 *
 * projectName: denormalized from order.project.name.
 * customerName: denormalized from order.project.customer.name — two levels deep.
 * MapStruct handles this with nested @Mapping expressions.
 */
public record OrderResponse(
        Long id,
        String orderNumber,
        OrderStatus status,
        LocalDate startDate,
        LocalDate endDate,
        BigDecimal totalAmount,
        Long projectId,
        String projectName,     // order.project.name
        String customerName,    // order.project.customer.name
        LocalDateTime createdAt
) {}
