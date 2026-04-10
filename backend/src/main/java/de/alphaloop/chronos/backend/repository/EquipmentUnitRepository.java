package de.alphaloop.chronos.backend.repository;

import de.alphaloop.chronos.backend.domain.EquipmentUnit;
import de.alphaloop.chronos.backend.enums.EquipmentStatus;
import de.alphaloop.chronos.backend.enums.EquipmentType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Repository for EquipmentUnit entity.
 *
 * This is arguably the most performance-critical repository in the system.
 * The availability check query runs on EVERY order creation attempt.
 * At Lang GmbH with 200+ concurrent users, this query executes hundreds of times per minute.
 *
 * Key performance requirements:
 *   - Availability check: must use the composite index (equipment_id, start_date, end_date)
 *   - Type + status filter: must use the composite index (type, status)
 *   - SKU lookup: must use the unique index (sku)
 *
 * Note: all these indexes are defined in @Table(indexes = {...}) on EquipmentUnit.
 * The queries here are written to make use of those indexes.
 */
@Repository
public interface EquipmentUnitRepository extends JpaRepository<EquipmentUnit, Long> {

    // ── Master Data Lookups ───────────────────────────────────────────────────

    // SKU is the business identifier — used in barcode scanning at Lang GmbH.
    // The unique index on 'sku' makes this a single B-tree lookup.
    Optional<EquipmentUnit> findBySku(String sku);

    boolean existsBySku(String sku);

    // ── Catalog Queries (filtered lists for UI) ───────────────────────────────
    //
    // Use case: equipment catalog page — filter by type, search by name.
    // idx_equipment_type_status composite index covers both filters.

    Page<EquipmentUnit> findByType(EquipmentType type, Pageable pageable);

    Page<EquipmentUnit> findByStatus(EquipmentStatus status, Pageable pageable);

    Page<EquipmentUnit> findByTypeAndStatus(EquipmentType type, EquipmentStatus status, Pageable pageable);

    // ── THE CORE AVAILABILITY QUERY ───────────────────────────────────────────
    //
    // This is the most important query in the entire rental system.
    // "Find all available equipment of a given type for the requested period."
    //
    // ALGORITHM EXPLAINED:
    // We want equipment where NO active Availability record overlaps our requested dates.
    // "Overlap" formula: existingStart < requestedEnd AND existingEnd > requestedStart
    //
    // This is a NOT EXISTS subquery — the most correct approach:
    //   - NOT IN with a subquery can fail on NULLs (SQL NULL gotcha)
    //   - LEFT JOIN / IS NULL is equivalent but less readable
    //   - NOT EXISTS is the canonical, null-safe, performant pattern
    //
    // The subquery hits the composite index: equipment_id, start_date, end_date
    //   1. Hibernate binds equipment_id → index narrows to that equipment's records
    //   2. start_date < :requestedEnd → index range scan (not full table scan)
    //   3. end_date > :requestedStart → further filtering
    //
    // AND eu.status = 'AVAILABLE':
    //   Extra guard for equipment flagged as MAINTENANCE or RETIRED at the entity level.
    //   This catches the case where status wasn't updated properly (defensive programming).
    //
    // AND a.status != 'CANCELLED':
    //   Cancelled reservations don't block the equipment. We keep them for audit history
    //   (see Availability.java comments), so we must explicitly exclude them from conflict check.
    //
    // countQuery: mandatory for Page<> queries to calculate total pages.
    // Without it, Spring wraps the full query in SELECT COUNT(*) FROM (...),
    // which breaks on queries with ORDER BY clause.

    @Query(
            value = """
                    SELECT eu FROM EquipmentUnit eu
                    WHERE eu.type = :type
                    AND eu.status = de.alphaloop.chronos.backend.enums.EquipmentStatus.AVAILABLE
                    AND NOT EXISTS (
                        SELECT 1 FROM Availability a
                        WHERE a.equipment = eu
                        AND a.startDate < :requestedEnd
                        AND a.endDate > :requestedStart
                        AND a.status != de.alphaloop.chronos.backend.enums.AvailabilityStatus.CANCELLED
                    )
                    ORDER BY eu.sku ASC
                    """,
            countQuery = """
                    SELECT COUNT(eu) FROM EquipmentUnit eu
                    WHERE eu.type = :type
                    AND eu.status = de.alphaloop.chronos.backend.enums.EquipmentStatus.AVAILABLE
                    AND NOT EXISTS (
                        SELECT 1 FROM Availability a
                        WHERE a.equipment = eu
                        AND a.startDate < :requestedEnd
                        AND a.endDate > :requestedStart
                        AND a.status != de.alphaloop.chronos.backend.enums.AvailabilityStatus.CANCELLED
                    )
                    """
    )
    Page<EquipmentUnit> findAvailableByTypeAndPeriod(
            @Param("type") EquipmentType type,
            @Param("requestedStart") LocalDate requestedStart,
            @Param("requestedEnd") LocalDate requestedEnd,
            Pageable pageable
    );

    // ── Availability Check (boolean) ──────────────────────────────────────────
    //
    // Use case: before adding an item to an order, check a SPECIFIC unit.
    // "Is projector Epson#3 (id=8842) free from April 13 to April 14?"
    //
    // This query is called by OrderValidationService before confirming an order.
    // It runs INSIDE the same transaction as the order creation — this is the
    // second check (first was the UI search). Two checks prevent race conditions:
    //
    // Timeline of a race condition WITHOUT double-check:
    //   T=0: User A searches → Epson#3 appears as available ✓
    //   T=0: User B searches → Epson#3 appears as available ✓
    //   T=1: User A confirms → Availability created ✓
    //   T=1: User B confirms → NO CHECK → two Availability records for same equipment!
    //
    // With double-check (this method called inside @Transactional service):
    //   T=1: User A confirms → check → available → Availability created → COMMIT
    //   T=1: User B confirms → check → NOT available → 409 Conflict returned ✓
    //
    // WHY not rely on a database UNIQUE constraint instead?
    // Date range overlap can't be expressed as a simple unique constraint.
    // PostgreSQL has EXCLUDE constraint with GiST index for this, but it requires
    // the daterange type — complex JPA mapping. For MVP, the application-level check
    // inside a serializable transaction is sufficient.

    @Query("""
            SELECT COUNT(a) > 0 FROM Availability a
            WHERE a.equipment.id = :equipmentId
            AND a.startDate < :requestedEnd
            AND a.endDate > :requestedStart
            AND a.status != de.alphaloop.chronos.backend.enums.AvailabilityStatus.CANCELLED
            """)
    boolean isEquipmentBookedDuringPeriod(
            @Param("equipmentId") Long equipmentId,
            @Param("requestedStart") LocalDate requestedStart,
            @Param("requestedEnd") LocalDate requestedEnd
    );

    // ── Calendar View Query ───────────────────────────────────────────────────
    //
    // Use case: Equipment calendar — show all equipment with their bookings for a month.
    // Used by the "Equipment Calendar" frontend component.
    //
    // WHY load availabilityRecords here?
    // The calendar component needs: equipment name + sku + all bookings in the month.
    // Without this JOIN FETCH, accessing eu.getAvailabilityRecords() for each equipment
    // would trigger N+1 queries (one per equipment unit).
    //
    // Filter by date overlap: only load availability records relevant to the calendar period.
    // Without the date filter, we'd load ALL historical records — could be years of history.
    //
    // Note: this is a JOIN FETCH with a WHERE condition on the collection — this is
    // possible in JPQL but requires the condition in the ON clause equivalent.
    // Hibernate handles this correctly with the WHERE clause filtering the joined records.

    @Query("""
            SELECT DISTINCT eu FROM EquipmentUnit eu
            LEFT JOIN FETCH eu.availabilityRecords a
            WHERE eu.status != de.alphaloop.chronos.backend.enums.EquipmentStatus.RETIRED
            AND (a IS NULL
                 OR (a.startDate < :monthEnd AND a.endDate > :monthStart
                     AND a.status != de.alphaloop.chronos.backend.enums.AvailabilityStatus.CANCELLED))
            ORDER BY eu.type ASC, eu.sku ASC
            """)
    List<EquipmentUnit> findAllWithAvailabilityForMonth(
            @Param("monthStart") LocalDate monthStart,
            @Param("monthEnd") LocalDate monthEnd
    );
}