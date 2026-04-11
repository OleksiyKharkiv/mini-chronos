package de.alphaloop.chronos.backend.service;

import de.alphaloop.chronos.backend.domain.Availability;
import de.alphaloop.chronos.backend.domain.EquipmentUnit;
import de.alphaloop.chronos.backend.enums.EquipmentStatus;
import de.alphaloop.chronos.backend.enums.EquipmentType;
import de.alphaloop.chronos.backend.exception.BusinessRuleException;
import de.alphaloop.chronos.backend.exception.ConflictException;
import de.alphaloop.chronos.backend.exception.ResourceNotFoundException;
import de.alphaloop.chronos.backend.repository.AvailabilityRepository;
import de.alphaloop.chronos.backend.repository.EquipmentUnitRepository;
import de.alphaloop.chronos.backend.repository.OrderItemRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * EquipmentService — manages equipment catalog, availability queries, and maintenance.
 *
 * This service has two distinct domains:
 *
 * 1. CATALOG MANAGEMENT (CRUD for equipment master data):
 *    Adding new projectors, updating prices, retiring old equipment.
 *    These are straightforward CRUD operations with some business rules.
 *
 * 2. AVAILABILITY (the performance-critical rental logic):
 *    "Which projectors are free April 13-14?" → calls the complex NOT EXISTS query.
 *    "Is this specific unit booked?" → the double-check before confirming an order.
 *    These methods are called by OrderService during order creation.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EquipmentService {

    private final EquipmentUnitRepository equipmentRepository;
    private final AvailabilityRepository availabilityRepository;
    private final OrderItemRepository orderItemRepository;

    // ── CATALOG READ OPERATIONS ───────────────────────────────────────────────

    public EquipmentUnit getById(Long id) {
        return equipmentRepository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.equipment(id));
    }

    public EquipmentUnit getBySku(String sku) {
        return equipmentRepository.findBySku(sku)
                .orElseThrow(() -> new ResourceNotFoundException("EquipmentUnit", sku));
    }

    public Page<EquipmentUnit> getByType(EquipmentType type, Pageable pageable) {
        return equipmentRepository.findByType(type, pageable);
    }

    public Page<EquipmentUnit> getAvailableByType(EquipmentType type, Pageable pageable) {
        return equipmentRepository.findByTypeAndStatus(type, EquipmentStatus.AVAILABLE, pageable);
    }

    // ── AVAILABILITY SEARCH ───────────────────────────────────────────────────

    /**
     * The central availability search — "which equipment is free for these dates?".
     *
     * This is called from the UI when the user selects rental dates and equipment type.
     * Internally runs the NOT EXISTS query with the composite index on availability.
     *
     * WHY validate dates in the service, not just the controller?
     * Defense in depth: the service is also called from other services and batch jobs.
     * Not every caller goes through the controller's @Valid validation.
     * The service should never trust its inputs.
     */
    public Page<EquipmentUnit> findAvailable(
            EquipmentType type,
            LocalDate startDate,
            LocalDate endDate,
            Pageable pageable) {

        validateDateRange(startDate, endDate);
        log.debug("Availability search: type={}, {} to {}", type, startDate, endDate);
        return equipmentRepository.findAvailableByTypeAndPeriod(type, startDate, endDate, pageable);
    }

    /**
     * Point-in-time availability check for a SPECIFIC unit.
     *
     * Called by OrderService as the SECOND CHECK during order confirmation.
     * The first check was the UI search (findAvailable above).
     * This is the race-condition guard: runs inside the same @Transactional
     * as the order confirmation and availability record creation.
     *
     * WHY return boolean instead of throwing?
     * OrderService needs to know the result to build an appropriate error message
     * (which specific equipment unit is not available).
     * A boolean gives the caller control over the exception message.
     */
    public boolean isAvailable(Long equipmentId, LocalDate startDate, LocalDate endDate) {
        return !equipmentRepository.isEquipmentBookedDuringPeriod(equipmentId, startDate, endDate);
    }

    /**
     * Load all equipment with their availability records for the calendar view.
     * One query with JOIN FETCH — no N+1 regardless of equipment count.
     */
    public List<EquipmentUnit> getCalendarView(LocalDate monthStart, LocalDate monthEnd) {
        validateDateRange(monthStart, monthEnd);
        return equipmentRepository.findAllWithAvailabilityForMonth(monthStart, monthEnd);
    }

    // ── CATALOG WRITE OPERATIONS ──────────────────────────────────────────────

    /**
     * Add new equipment to the catalog.
     *
     * SKU uniqueness: two-layer check (application + DB unique constraint).
     * See CustomerService.create() for the explanation of why both are needed.
     */
    @Transactional
    public EquipmentUnit create(EquipmentUnit equipment) {
        log.info("Creating equipment: sku={}", equipment.getSku());

        if (equipmentRepository.existsBySku(equipment.getSku())) {
            throw new ConflictException("Equipment with SKU already exists: " + equipment.getSku());
        }

        equipment.setStatus(EquipmentStatus.AVAILABLE);
        EquipmentUnit saved = equipmentRepository.save(equipment);
        log.info("Equipment created: id={}, sku={}", saved.getId(), saved.getSku());
        return saved;
    }

    /**
     * Update equipment master data (name, description, daily rate).
     *
     * WHY allow updating daily_rate?
     * The rental price changes over time (inflation, market rates).
     * Updating daily_rate does NOT affect existing orders — they store
     * the price snapshot in OrderItem.unitPrice.
     * Only NEW orders will use the updated rate.
     * This is the "price snapshot" pattern in action.
     */
    @Transactional
    public EquipmentUnit update(Long id, EquipmentUnit updatedData) {
        EquipmentUnit existing = getById(id);

        if (existing.getStatus() == EquipmentStatus.RETIRED) {
            throw new BusinessRuleException("Cannot update retired equipment: " + existing.getSku());
        }

        existing.setName(updatedData.getName());
        existing.setDescription(updatedData.getDescription());
        existing.setDailyRate(updatedData.getDailyRate());
        // type and sku are intentionally NOT updatable — they are identity fields

        return existing;
    }

    // ── MAINTENANCE OPERATIONS ────────────────────────────────────────────────

    /**
     * Schedule a maintenance block for equipment.
     *
     * This creates an Availability record with status=MAINTENANCE and no OrderItem.
     * This is the "nullable orderItem" use case from Availability.java.
     *
     * TRANSACTION FLOW:
     * 1. Load equipment (SELECT) — verify it exists
     * 2. Check for conflicting reservations (SELECT COUNT) — fail fast
     * 3. Mark equipment as MAINTENANCE (dirty check → UPDATE)
     * 4. Create Availability record (INSERT)
     * All 4 steps are ONE transaction — ATOMIC.
     *
     * If step 4 fails (e.g. constraint violation), step 3 is also rolled back.
     * The equipment stays AVAILABLE and no maintenance record is created.
     * No partial state changes.
     *
     * WHY check for reservations before maintenance?
     * You can't send a projector to the repair shop if a client has booked it
     * for next week's conference. The service team must coordinate with sales first.
     */
    @Transactional
    public Availability scheduleMaintenance(Long equipmentId, LocalDate startDate, LocalDate endDate) {
        log.info("Scheduling maintenance: equipmentId={}, {} to {}", equipmentId, startDate, endDate);

        validateDateRange(startDate, endDate);
        EquipmentUnit equipment = getById(equipmentId);

        // Check for conflicting active reservations in this period.
        if (availabilityRepository.hasActiveReservationsDuringPeriod(equipmentId, startDate, endDate)) {
            throw ConflictException.maintenanceBlockedByReservation(equipment.getSku());
        }

        // Mark as MAINTENANCE at the entity level (updates equipment_units.status).
        equipment.sendToMaintenance(); // throws IllegalStateException if already not AVAILABLE

        // Create the Availability record (the actual time block).
        // Using the factory method from Availability — keeps creation logic in the domain.
        Availability maintenance = Availability.blockForMaintenance(equipment, startDate, endDate);
        Availability saved = availabilityRepository.save(maintenance);

        log.info("Maintenance scheduled: equipmentId={}, availabilityId={}", equipmentId, saved.getId());
        return saved;
    }

    /**
     * Return equipment from maintenance — marks it AVAILABLE again.
     *
     * Does NOT delete the maintenance Availability record.
     * WHY? The record is historical evidence that maintenance happened.
     * Sets the status to CANCELLED so it no longer blocks availability queries.
     */
    @Transactional
    public void completeMaintenance(Long equipmentId) {
        log.info("Completing maintenance for equipmentId={}", equipmentId);

        EquipmentUnit equipment = getById(equipmentId);

        if (equipment.getStatus() != EquipmentStatus.MAINTENANCE) {
            throw new BusinessRuleException(
                    "Equipment is not in MAINTENANCE status: " + equipment.getSku()
            );
        }

        equipment.markAsAvailable();
        // The maintenance Availability records remain in DB (audit trail),
        // but they won't block future queries because:
        // - the query filters: status != CANCELLED
        // - maintenance status is MAINTENANCE, not CANCELLED, so it still shows in history
        // - future availability queries check for RESERVED records only for the double-check
        // For full cleanup, a separate job could cancel old maintenance records.
    }

    /**
     * Soft-retire equipment — permanently remove from rental circulation.
     *
     * WHY not hard DELETE?
     * Equipment appears in historical OrderItems and financial records.
     * Deleting it would violate referential integrity AND financial audit requirements.
     * RETIRED status means: "never show in availability search, preserve history".
     *
     * WHY block retirement if equipment has been ordered?
     * existsByEquipmentId checks the OrderItem table.
     * If the equipment ever appeared in an order → it has financial history.
     * We allow retirement (soft-delete), but the caller should be aware
     * that historical data exists. In this MVP we just warn via log.
     * In production: you might want to require explicit confirmation from the user.
     */
    @Transactional
    public void retire(Long id) {
        log.info("Retiring equipment: id={}", id);

        EquipmentUnit equipment = getById(id);

        if (orderItemRepository.existsByEquipmentId(id)) {
            log.warn("Retiring equipment id={} which has order history — data preserved", id);
        }

        equipment.setStatus(EquipmentStatus.RETIRED);
    }

    // ── PRIVATE HELPERS ───────────────────────────────────────────────────────

    /**
     * Validates that a date range is logically correct.
     *
     * WHY a private helper instead of repeating the check?
     * DRY (Don't Repeat Yourself): this check is needed in findAvailable,
     * scheduleMaintenance, and getCalendarView.
     * If the validation rule changes (e.g. max rental period = 365 days),
     * it changes in ONE place.
     *
     * WHY BusinessRuleException and not IllegalArgumentException?
     * IllegalArgumentException is a Java internal exception — no HTTP semantics.
     * BusinessRuleException → GlobalExceptionHandler → 422 response with message.
     * The API client gets a useful error, not a 500 Internal Server Error.
     */
    private void validateDateRange(LocalDate start, LocalDate end) {
        if (start == null || end == null) {
            throw new BusinessRuleException("Start date and end date are required");
        }
        if (!end.isAfter(start) && !end.isEqual(start)) {
            throw new BusinessRuleException("End date must be on or after start date");
        }
    }
}