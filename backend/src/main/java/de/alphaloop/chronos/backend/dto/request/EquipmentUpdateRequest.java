package de.alphaloop.chronos.backend.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Update request: only mutable fields.
 * SKU and type are identity fields — they cannot change after creation.
 * (Changing SKU would break barcode labels already printed in the warehouse.)
 */
public record EquipmentUpdateRequest(

        @NotBlank(message = "Equipment name is required")
        @Size(max = 255)
        String name,

        @Size(max = 1000)
        String description,

        @DecimalMin(value = "0.0", inclusive = true)
        @Digits(integer = 8, fraction = 2)
        BigDecimal dailyRate

) {
}