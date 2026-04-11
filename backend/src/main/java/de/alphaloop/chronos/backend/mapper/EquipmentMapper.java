package de.alphaloop.chronos.backend.mapper;

import de.alphaloop.chronos.backend.domain.Availability;
import de.alphaloop.chronos.backend.domain.EquipmentUnit;
import de.alphaloop.chronos.backend.dto.request.EquipmentCreateRequest;
import de.alphaloop.chronos.backend.dto.request.EquipmentUpdateRequest;
import de.alphaloop.chronos.backend.dto.response.AvailabilitySlotResponse;
import de.alphaloop.chronos.backend.dto.response.EquipmentCalendarResponse;
import de.alphaloop.chronos.backend.dto.response.EquipmentResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

import java.util.List;

/**
 * EquipmentMapper — demonstrates mapping with nullable nested fields.
 *
 * The interesting case: AvailabilitySlotResponse has "orderItemId" (nullable).
 * It comes from availability.getOrderItem().getId() — but orderItem can be NULL
 * (maintenance blocks have no order item).
 *
 * MapStruct handles nullable nested paths safely:
 * @Mapping(source = "orderItem.id", target = "orderItemId")
 * Generated code:
 *   Long orderItemId = null;
 *   if (availability.getOrderItem() != null) {
 *       orderItemId = availability.getOrderItem().getId();
 *   }
 *   // if orderItem is null → orderItemId remains null → correct!
 *
 * This mirrors the domain model: Availability.orderItem is nullable,
 * AvailabilitySlotResponse.orderItemId is nullable (Long, not long).
 * The mapping is semantically correct and null-safe.
 */
@Mapper(componentModel = "spring")
public interface EquipmentMapper {

    EquipmentResponse toResponse(EquipmentUnit equipment);

    List<EquipmentResponse> toResponseList(List<EquipmentUnit> equipment);

    /**
     * Calendar response: equipment + its availability slots for a month.
     *
     * availabilityRecords → slots: field name mismatch.
     * Entity field: equipment.availabilityRecords (List<Availability>)
     * DTO field:    EquipmentCalendarResponse.slots (List<AvailabilitySlotResponse>)
     *
     * @Mapping(source = "availabilityRecords", target = "slots"):
     * MapStruct renames the field AND converts each Availability → AvailabilitySlotResponse
     * using toSlotResponse() defined below — found automatically by type matching.
     */
    @Mapping(source = "availabilityRecords", target = "slots")
    EquipmentCalendarResponse toCalendarResponse(EquipmentUnit equipment);

    List<EquipmentCalendarResponse> toCalendarResponseList(List<EquipmentUnit> equipmentList);

    /**
     * Availability → AvailabilitySlotResponse.
     * Nullable nested path: orderItem.id → orderItemId.
     * MapStruct generates a null-safe traversal (see class javadoc above).
     */
    @Mapping(source = "orderItem.id", target = "orderItemId")
    AvailabilitySlotResponse toSlotResponse(Availability availability);

    // ── Request → Entity ─────────────────────────────────────────────────────

    @Mapping(target = "id",                   ignore = true)
    @Mapping(target = "status",               ignore = true)  // always AVAILABLE on creation
    @Mapping(target = "availabilityRecords",   ignore = true)
    @Mapping(target = "createdAt",            ignore = true)
    @Mapping(target = "updatedAt",            ignore = true)
    EquipmentUnit toEntity(EquipmentCreateRequest request);

    /**
     * Update: only name, description, dailyRate can change.
     * SKU and type are identity fields — immutable after creation.
     * (SKU is printed on barcode labels, type defines how the equipment is categorized)
     */
    @Mapping(target = "id",                   ignore = true)
    @Mapping(target = "sku",                  ignore = true)
    @Mapping(target = "type",                 ignore = true)
    @Mapping(target = "status",               ignore = true)
    @Mapping(target = "availabilityRecords",   ignore = true)
    @Mapping(target = "createdAt",            ignore = true)
    @Mapping(target = "updatedAt",            ignore = true)
    void updateEntity(EquipmentUpdateRequest request, @MappingTarget EquipmentUnit existing);
}