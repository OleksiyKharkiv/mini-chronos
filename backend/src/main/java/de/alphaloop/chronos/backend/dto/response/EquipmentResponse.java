package de.alphaloop.chronos.backend.dto.response;

import de.alphaloop.chronos.backend.enums.EquipmentStatus;
import de.alphaloop.chronos.backend.enums.EquipmentType;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Equipment response for catalog lists and availability search results.
 */
public record EquipmentResponse(
        Long id,
        String sku,
        String name,
        String description,
        EquipmentType type,
        EquipmentStatus status,
        BigDecimal dailyRate,
        LocalDateTime createdAt
) {}
