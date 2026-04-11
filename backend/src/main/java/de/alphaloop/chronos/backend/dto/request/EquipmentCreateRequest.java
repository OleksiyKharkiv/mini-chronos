package de.alphaloop.chronos.backend.dto.request;

import de.alphaloop.chronos.backend.enums.EquipmentType;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;

public record EquipmentCreateRequest(

        @NotBlank(message = "SKU is required")
        @Size(max = 100, message = "SKU must not exceed 100 characters")
        String sku,

        @NotBlank(message = "Equipment name is required")
        @Size(max = 255)
        String name,

        @Size(max = 1000)
        String description,

        @NotNull(message = "Equipment type is required")
        EquipmentType type,

        // Daily rate is optional at creation (equipment catalog entry before pricing).
        @DecimalMin(value = "0.0", inclusive = true, message = "Daily rate cannot be negative")
        @Digits(integer = 8, fraction = 2, message = "Daily rate format: max 8 digits, 2 decimal places")
        BigDecimal dailyRate

) {}