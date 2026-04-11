package de.alphaloop.chronos.backend.dto.request;

// ─── ORDER ───────────────────────────────────────────────────────────────────

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

/**
 * Request for creating a draft order.
 * <p>
 * Contains a nested list of items — this is a "compound request".
 * Jackson deserializes the JSON array into List<OrderItemRequest> automatically.
 *
 * @Valid on the list: tells Jakarta Validation to validate EACH element
 * of the list, not just check if the list is non-null.
 * Without @Valid: quantity=-5 would pass validation silently!
 * <p>
 * JSON example:
 * {
 *   "projectId": 300,
 *   "startDate": "2026-04-13",
 *   "endDate": "2026-04-14",
 *   "items": [
 *     {"equipmentId": 200, "quantity": 1},
 *     {"equipmentId": 201, "quantity": 1}
 *   ]
 * }
 */
public record OrderCreateRequest(

        @NotNull(message = "Project ID is required")
        Long projectId,

        @NotNull(message = "Start date is required")
        LocalDate startDate,

        @NotNull(message = "End date is required")
        LocalDate endDate,

        @NotNull(message = "Order must have at least one item")
        @Size(min = 1, message = "Order must contain at least one item")
        @Valid                      // ← validates each element inside the list
        List<OrderItemRequest> items

) {
    /**
     * Nested record for a single order line item.
     * <p>
     * Inner record inside the outer record — valid Java syntax.
     * Keeps related types together: OrderCreateRequest always comes with its items.
     */
    public record OrderItemRequest(

            @NotNull(message = "Equipment ID is required")
            Long equipmentId,

            @Min(value = 1, message = "Quantity must be at least 1")
            int quantity

    ) {}
}