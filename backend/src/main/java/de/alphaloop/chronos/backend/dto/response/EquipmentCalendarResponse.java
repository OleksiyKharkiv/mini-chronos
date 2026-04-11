package de.alphaloop.chronos.backend.dto.response;

import de.alphaloop.chronos.backend.enums.EquipmentStatus;
import de.alphaloop.chronos.backend.enums.EquipmentType;

import java.util.List;

/**
 * Equipment response for the calendar view.
 * Includes availability slots for the requested month.
 *
 * Each slot represents a blocked period: RESERVED, MAINTENANCE, or BLOCKED.
 * The frontend uses this to render the equipment availability calendar.
 */
public record EquipmentCalendarResponse(
        Long id,
        String sku,
        String name,
        EquipmentType type,
        EquipmentStatus status,
        List<AvailabilitySlotResponse> slots  // availability for the requested month
) {}