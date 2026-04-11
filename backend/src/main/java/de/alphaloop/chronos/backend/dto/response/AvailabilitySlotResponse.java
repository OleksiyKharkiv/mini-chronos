package de.alphaloop.chronos.backend.dto.response;

import de.alphaloop.chronos.backend.enums.AvailabilityStatus;

import java.time.LocalDate;

/**
 * A single availability slot — one blocked period for one equipment.
 * Used in EquipmentCalendarResponse.
 * <p>
 * orderItemId: nullable. null = maintenance/admin block (no order).
 * This directly mirrors the nullable FK in the Availability entity.
 */
public record AvailabilitySlotResponse(
        Long id,
        LocalDate startDate,
        LocalDate endDate,
        AvailabilityStatus status,
        Long orderItemId    // nullable: null means maintenance, not null means reservation
) {}