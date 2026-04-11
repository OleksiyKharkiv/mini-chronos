package de.alphaloop.chronos.backend.dto.response;

import java.math.BigDecimal;

/**
 * Order line item response.
 * <p>
 * lineTotal: a COMPUTED field — quantity × unitPrice.
 * Not stored in DB, calculated in OrderItem.getLineTotal().
 * The mapper calls this method and puts the result in the DTO.
 * <p>
 * We include both equipmentId (for navigation) and equipmentSku + equipmentName
 * (for display) — avoids a second request from the client to look up equipment.
 */
public record OrderItemResponse(
        Long id,
        Long equipmentId,
        String equipmentSku,    // denormalized from item.equipment.sku
        String equipmentName,   // denormalized from item.equipment.name
        int quantity,
        BigDecimal unitPrice,
        BigDecimal lineTotal    // computed: quantity × unitPrice
) {}