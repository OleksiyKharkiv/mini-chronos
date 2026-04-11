package de.alphaloop.chronos.backend.dto.response;

import de.alphaloop.chronos.backend.enums.OrderStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Rich order response for the order detail page.
 * Includes all line items with their equipment details.
 * <p>
 * version: included so the client can send it back in update requests.
 * This is how optimistic locking works in REST APIs:
 *   GET /api/orders/42 → response includes "version": 3
 *   PUT /api/orders/42 with body {"version": 3, ...}
 *   → server checks that the current DB version == 3 before updating
 *   → if the version != 3 → 409 Conflict (someone else changed it)
 * <p>
 * The client MUST echo back the version it received.
 * Without this, the client cannot participate in the optimistic locking protocol.
 */
public record OrderDetailResponse(
        Long id,
        String orderNumber,
        OrderStatus status,
        LocalDate startDate,
        LocalDate endDate,
        BigDecimal totalAmount,
        Long version,           // ← for optimistic locking on update
        Long projectId,
        String projectName,
        Long customerId,
        String customerName,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        List<OrderItemResponse> items
) {}