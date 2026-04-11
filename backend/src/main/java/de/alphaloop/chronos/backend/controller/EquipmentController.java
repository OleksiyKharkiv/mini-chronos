package de.alphaloop.chronos.backend.controller;

import de.alphaloop.chronos.backend.domain.EquipmentUnit;
import de.alphaloop.chronos.backend.dto.request.EquipmentCreateRequest;
import de.alphaloop.chronos.backend.dto.request.EquipmentUpdateRequest;
import de.alphaloop.chronos.backend.dto.request.MaintenanceRequest;
import de.alphaloop.chronos.backend.dto.response.EquipmentCalendarResponse;
import de.alphaloop.chronos.backend.dto.response.EquipmentResponse;
import de.alphaloop.chronos.backend.enums.EquipmentType;
import de.alphaloop.chronos.backend.mapper.EquipmentMapper;
import de.alphaloop.chronos.backend.service.EquipmentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.time.LocalDate;
import java.util.List;

/**
 * EquipmentController — REST API for Equipment catalog and availability.
 *
 * Two conceptually different areas in one controller:
 *
 * 1. CATALOG (CRUD): add/update/retire equipment units.
 *    Typical user: admin or warehouse manager.
 *    Frequency: low (new equipment arrives occasionally).
 *
 * 2. AVAILABILITY (read-heavy queries): search free equipment, view calendar.
 *    Typical user: Maria Müller (Vertrieb) creating orders.
 *    Frequency: HIGH — called on every order creation attempt.
 *    Performance-critical: hits the NOT EXISTS query with composite index.
 *
 * These could be split into EquipmentCatalogController and EquipmentAvailabilityController,
 * but for MVP they live together — the boundary is clear in the URL structure:
 *   /api/equipment          → catalog
 *   /api/equipment/available → availability search
 *   /api/equipment/calendar  → calendar view
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/equipment")
public class EquipmentController {

    private final EquipmentService  equipmentService;
    private final EquipmentMapper   equipmentMapper;

    // ── CATALOG ENDPOINTS ─────────────────────────────────────────────────────

    @GetMapping("/{id}")
    public ResponseEntity<EquipmentResponse> getById(@PathVariable Long id) {
        return ResponseEntity.ok(
                equipmentMapper.toResponse(equipmentService.getById(id))
        );
    }

    /**
     * Catalog list filtered by type.
     * type is optional: if omitted, you'd need a different service method.
     * For MVP: type is required (the UI always filters by type).
     */
    @GetMapping
    public ResponseEntity<Page<EquipmentResponse>> list(
            @RequestParam EquipmentType type,
            @PageableDefault(size = 20, sort = "sku") Pageable pageable
    ) {
        Page<EquipmentUnit> page = equipmentService.getByType(type, pageable);
        return ResponseEntity.ok(page.map(equipmentMapper::toResponse));
    }

    @PostMapping
    public ResponseEntity<EquipmentResponse> create(
            @RequestBody @Valid EquipmentCreateRequest request
    ) {
        EquipmentUnit equipment = equipmentMapper.toEntity(request);
        EquipmentUnit saved     = equipmentService.create(equipment);

        URI location = ServletUriComponentsBuilder
                .fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(saved.getId())
                .toUri();

        return ResponseEntity.created(location).body(equipmentMapper.toResponse(saved));
    }

    @PutMapping("/{id}")
    public ResponseEntity<EquipmentResponse> update(
            @PathVariable Long id,
            @RequestBody @Valid EquipmentUpdateRequest request
    ) {
        EquipmentUnit existing = equipmentService.getById(id);
        equipmentMapper.updateEntity(request, existing);
        EquipmentUnit updated  = equipmentService.update(id, existing);
        return ResponseEntity.ok(equipmentMapper.toResponse(updated));
    }

    /**
     * Retire equipment — soft-delete.
     * Returns 204 No Content (same convention as customer deactivation).
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> retire(@PathVariable Long id) {
        equipmentService.retire(id);
        return ResponseEntity.noContent().build();
    }

    // ── AVAILABILITY ENDPOINTS ────────────────────────────────────────────────

    /**
     * Search for available equipment of a given type in a date range.
     *
     * GET /api/equipment/available?type=PROJECTOR&start=2026-04-13&end=2026-04-14
     *
     * @DateTimeFormat(iso = DateTimeFormat.ISO.DATE):
     * Tells Spring how to parse the date query parameter.
     * ISO format: "2026-04-13" (yyyy-MM-dd).
     * Without this annotation: Spring can't parse LocalDate from query params
     * and throws a MethodArgumentTypeMismatchException.
     *
     * This is the endpoint called FIRST when the user selects rental dates.
     * Internally runs the NOT EXISTS query against the availability table.
     * Performance depends on the composite index (equipment_id, start_date, end_date).
     */
    @GetMapping("/available")
    public ResponseEntity<Page<EquipmentResponse>> findAvailable(
            @RequestParam EquipmentType type,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end,
            @PageableDefault(size = 20, sort = "sku") Pageable pageable
    ) {
        Page<EquipmentUnit> page = equipmentService.findAvailable(type, start, end, pageable);
        return ResponseEntity.ok(page.map(equipmentMapper::toResponse));
    }

    /**
     * Equipment calendar view for a given month.
     *
     * GET /api/equipment/calendar?start=2026-04-01&end=2026-04-30
     *
     * Returns equipment list WITH their availability slots pre-loaded.
     * The frontend renders the calendar grid from this data.
     *
     * Returns List, not Page: the calendar shows ALL equipment (not paginated).
     * The month constrains the data — at most 31 days of slots per unit.
     */
    @GetMapping("/calendar")
    public ResponseEntity<List<EquipmentCalendarResponse>> getCalendar(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end
    ) {
        List<EquipmentUnit> equipment = equipmentService.getCalendarView(start, end);
        return ResponseEntity.ok(equipmentMapper.toCalendarResponseList(equipment));
    }

    // ── MAINTENANCE ENDPOINTS ─────────────────────────────────────────────────

    /**
     * Schedule a maintenance window for specific equipment.
     *
     * POST /api/equipment/{id}/maintenance
     *
     * WHY POST and not PUT/PATCH?
     * We're not updating the equipment resource — we're CREATING a new
     * Availability record of type MAINTENANCE. A new resource is created → POST.
     *
     * Returns 201 Created with the maintenance Availability as confirmation.
     * The response could be an AvailabilitySlotResponse, but for MVP
     * we return EquipmentResponse (updated status = MAINTENANCE) — simpler.
     */
    @PostMapping("/{id}/maintenance")
    public ResponseEntity<EquipmentResponse> scheduleMaintenance(
            @PathVariable Long id,
            @RequestBody @Valid MaintenanceRequest request
    ) {
        // The service creates the Availability record AND updates equipment status.
        // We return the updated equipment state (status=MAINTENANCE) as confirmation.
        equipmentService.scheduleMaintenance(id, request.startDate(), request.endDate());
        EquipmentUnit updated = equipmentService.getById(id);
        return ResponseEntity.ok(equipmentMapper.toResponse(updated));
    }

    /**
     * Mark maintenance as complete — return equipment to AVAILABLE.
     *
     * PATCH /api/equipment/{id}/maintenance/complete
     *
     * A sub-action on the maintenance sub-resource.
     * Returns the updated equipment (status back to AVAILABLE).
     */
    @PatchMapping("/{id}/maintenance/complete")
    public ResponseEntity<EquipmentResponse> completeMaintenance(@PathVariable Long id) {
        equipmentService.completeMaintenance(id);
        EquipmentUnit updated = equipmentService.getById(id);
        return ResponseEntity.ok(equipmentMapper.toResponse(updated));
    }
}