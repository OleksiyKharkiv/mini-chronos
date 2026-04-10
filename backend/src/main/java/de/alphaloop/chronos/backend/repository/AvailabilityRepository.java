package de.alphaloop.chronos.backend.repository;

import de.alphaloop.chronos.backend.domain.Availability;
import de.alphaloop.chronos.backend.enums.AvailabilityStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

/**
 * Repository for Availability entity.
 * <p>
 * Availability records are the "lock table" of the rental system.
 * They represent time-based reservations on equipment.
 * <p>
 * LIFECYCLE managed by OrderService:
 *   OrderItem created → Availability.reserveForOrderItem() → availabilityRepo.save()
 *   Order cancelled   → availabilityRepo.cancelByOrderItemId()  (soft-cancel, keep history)
 *   Maintenance block → Availability.blockForMaintenance()      → availabilityRepo.save()
 * <p>
 * This repository is intentionally minimal — the complex availability search
 * lives in EquipmentUnitRepository (findAvailableByTypeAndPeriod, isEquipmentBookedDuringPeriod)
 * because those queries are equipment-centric, not availability-centric.
 */
@Repository
public interface AvailabilityRepository extends JpaRepository<Availability, Long> {

    // ── Lookup by Order Item ──────────────────────────────────────────────────
    //
    // Use case: when an order is cancelled, cancel all its availability records.
    // We have the orderId → traverse: Order → OrderItems → Availability records.
    //
    // WHY query by order_item_id directly instead of loading the graph?
    // Loading Order → items → availability for cancellation would require:
    //   - Loading Order entity with items (JOIN FETCH)
    //   - For each item, accessing item.getAvailabilityRecords() (another lazy load)
    //   Total: 2 queries minimum, potentially N+1 for availability records
    //
    // Direct query: one SELECT using idx_availability_order_itemid
    //   SELECT * FROM availability WHERE order_item_id = ?
    //   Uses the index → very fast.

    List<Availability> findByOrderItemId(Long orderItemId);

    // Also useful when working with a full Order object — get all availability
    // records for all items in this order in one query.
    // Traversal: availability.orderItem.order.id = :orderId
    // Generated SQL: JOIN order_items oi ON oi.id = a.order_item_id
    //                WHERE oi.order_id = :orderId

    List<Availability> findByOrderItemOrderId(Long orderId);

    // ── Equipment Availability History ────────────────────────────────────────
    //
    // Use case: "show me all future bookings for this projector"
    //           → service desk can warn: "it's booked until April 15"

    List<Availability> findByEquipmentIdAndStartDateGreaterThanEqual(
            Long equipmentId, LocalDate from
    );

    // ── Soft Cancellation (bulk) ──────────────────────────────────────────────
    //
    // Use case: order cancelled → set ALL its availability records to CANCELLED.
    //
    // WHY soft-cancel instead of DELETE?
    // Already explained in Availability entity: financial audit trail.
    // If a customer disputes "I never booked that weekend", we can show the
    // cancelled reservation that proves they did book (and then cancelled).
    //
    // WHY @Modifying bulk UPDATE instead of load-and-save?
    //
    // Load-and-save approach:
    //   List<Availability> records = findByOrderItemOrderId(orderId);  // SELECT
    //   records.forEach(a -> a.setStatus(CANCELLED));                  // in-memory
    //   saveAll(records);                                              // N UPDATEs
    //
    // Bulk UPDATE approach (this method):
    //   UPDATE availability SET status = 'CANCELLED'
    //   WHERE order_item_id IN (
    //       SELECT id FROM order_items WHERE order_id = :orderId
    //   )
    //   TOTAL: 1 query regardless of number of items!
    //
    // For an order with 10 items → 1 UPDATE vs 11 queries. At scale this matters.
    //
    // clearAutomatically = true: if any Availability entity is in the current
    // Hibernate session (first-level cache), it will have STALE status after this UPDATE.
    // clearAutomatically evicts them from cache so the next read hits the DB.

    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE Availability a
            SET a.status = de.alphaloop.chronos.backend.enums.AvailabilityStatus.CANCELLED
            WHERE a.orderItem.id IN (
                SELECT oi.id FROM OrderItem oi WHERE oi.order.id = :orderId
            )
            AND a.status != de.alphaloop.chronos.backend.enums.AvailabilityStatus.CANCELLED
            """)
    int cancelByOrderId(@Param("orderId") Long orderId);

    // Single item cancellation — when one item is removed from the order,
    // not the whole order cancelled.
    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE Availability a
            SET a.status = de.alphaloop.chronos.backend.enums.AvailabilityStatus.CANCELLED
            WHERE a.orderItem.id = :orderItemId
            AND a.status = de.alphaloop.chronos.backend.enums.AvailabilityStatus.RESERVED
            """)
    int cancelByOrderItemId(@Param("orderItemId") Long orderItemId);

    // ── Overlap Check (explicit — used in tests and service validation) ───────
    //
    // This is the same logic as EquipmentUnitRepository.isEquipmentBookedDuringPeriod()
    // but from the Availability side — useful in unit tests where you want to
    // verify the correct number of overlapping records, not just a boolean.

    @Query("""
            SELECT a FROM Availability a
            WHERE a.equipment.id = :equipmentId
            AND a.startDate < :requestedEnd
            AND a.endDate > :requestedStart
            AND a.status != de.alphaloop.chronos.backend.enums.AvailabilityStatus.CANCELLED
            """)
    List<Availability> findOverlappingRecords(
            @Param("equipmentId") Long equipmentId,
            @Param("requestedStart") LocalDate requestedStart,
            @Param("requestedEnd") LocalDate requestedEnd
    );

    // ── Maintenance Window Check ──────────────────────────────────────────────
    //
    // Use case: before scheduling maintenance, check if the equipment has active orders.
    // Service team wants to block equipment for maintenance, but logistics
    // may have already booked it for that period.

    @Query("""
            SELECT COUNT(a) > 0 FROM Availability a
            WHERE a.equipment.id = :equipmentId
            AND a.startDate < :maintenanceEnd
            AND a.endDate > :maintenanceStart
            AND a.status = de.alphaloop.chronos.backend.enums.AvailabilityStatus.RESERVED
            """)
    boolean hasActiveReservationsDuringPeriod(
            @Param("equipmentId") Long equipmentId,
            @Param("maintenanceStart") LocalDate maintenanceStart,
            @Param("maintenanceEnd") LocalDate maintenanceEnd
    );
}