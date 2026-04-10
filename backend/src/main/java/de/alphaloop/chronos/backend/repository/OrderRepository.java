package de.alphaloop.chronos.backend.repository;

import de.alphaloop.chronos.backend.domain.Order;
import de.alphaloop.chronos.backend.enums.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Repository for Order entity — the central aggregate in mini-chronos.
 *
 * Order is the most queried entity in any ERP system:
 *   - Sales team: "show all open orders"
 *   - Logistics: "what orders start this week?"
 *   - Finance: "total revenue for April 2026"
 *   - Customer service: "find order ORD-2026-00000042"
 *
 * Each use case requires a different projection and loading strategy.
 * This repository demonstrates the full range of Spring Data JPA patterns.
 */
@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {

    // ── Simple Lookups ────────────────────────────────────────────────────────

    // Business identifier lookup — customer calls support with order number.
    // Uses idx_order_number unique index → single row lookup.
    Optional<Order> findByOrderNumber(String orderNumber);

    // ── Filtered Lists with Pagination ───────────────────────────────────────
    //
    // WHY separate methods instead of one mega-query with all filters?
    // In Spring Data JPA, derived methods generate type-safe, compile-time-checked JPQL.
    // A single "find everything with optional filters" requires @Query with nullable params,
    // which is harder to test and maintain.
    // Use separate focused methods + let the service layer choose which one to call.

    Page<Order> findByStatus(OrderStatus status, Pageable pageable);

    Page<Order> findByProjectId(Long projectId, Pageable pageable);

    // Chain of relationships: Order → Project → Customer
    // Spring Data JPA traverses associations using '_' or camelCase nesting.
    // Generated JPQL: WHERE o.project.customer.id = :customerId
    // Hibernate generates: JOIN projects p ON p.id = o.project_id
    //                      JOIN customers c ON c.id = p.customer_id
    //                      WHERE c.id = ?
    Page<Order> findByProjectCustomerId(Long customerId, Pageable pageable);

    // ── Date-Range Queries ────────────────────────────────────────────────────
    //
    // Use case: Logistics dashboard — "what starts this week?"
    // Between is inclusive on both ends in Spring Data:
    //   WHERE o.start_date >= :start AND o.start_date <= :end
    //
    // WHY List and not Page here?
    // Logistics needs ALL orders for a specific week to plan shipments.
    // A week has at most 7 days, unlikely to return more than a few hundred rows.
    // Pagination would make the logistics workflow harder (need to "load more" for planning).
    // Use List when the result set is naturally bounded.

    List<Order> findByStartDateBetween(LocalDate start, LocalDate end);

    // Find orders that OVERLAP with a date range (not just start within).
    // Use case: "what orders are active during the week of April 13?"
    //
    // An order overlaps a range if:
    //   order.startDate <= rangeEnd  AND  order.endDate >= rangeStart
    //   (same formula as Availability.overlaps(), just for Order level)
    //
    // WHY @Query instead of derived method?
    // Derived method would be: findByStartDateLessThanEqualAndEndDateGreaterThanEqual(...)
    // That compiles, but readability is zero. @Query is clearer for complex conditions.

    @Query("""
            SELECT o FROM Order o
            WHERE o.startDate <= :rangeEnd
            AND o.endDate >= :rangeStart
            AND o.status NOT IN ('CANCELLED')
            ORDER BY o.startDate ASC
            """)
    List<Order> findActiveOrdersOverlappingPeriod(
            @Param("rangeStart") LocalDate rangeStart,
            @Param("rangeEnd") LocalDate rangeEnd
    );

    // ── EntityGraph: Order detail (full view with items) ─────────────────────
    //
    // Use case: OrderController.getOrderDetail() — shows order + all line items.
    //
    // Without EntityGraph — 1 + N queries where N = number of items:
    //   Query 1:  SELECT * FROM orders WHERE id = 42
    //   Query 2:  SELECT * FROM order_items WHERE order_id = 42
    //   Query 3:  SELECT * FROM equipment_units WHERE id = 8842  (item 1)
    //   Query 4:  SELECT * FROM equipment_units WHERE id = 8843  (item 2)
    //   ...up to 20+ queries for an order with many items!
    //
    // With EntityGraph ["items", "items.equipment"] — 1 query:
    //   SELECT o.*, oi.*, eu.*
    //   FROM orders o
    //   LEFT JOIN order_items oi ON oi.order_id = o.id
    //   LEFT JOIN equipment_units eu ON eu.id = oi.equipment_id
    //   WHERE o.id = :id
    //
    // "items.equipment": nested path — load items AND each item's equipment.
    // This is the "two-level" EntityGraph — Hibernate handles the nested JOIN.

    @Query("SELECT o FROM Order o WHERE o.id = :id")
    @EntityGraph(attributePaths = {"items", "items.equipment"})
    Optional<Order> findByIdWithItems(@Param("id") Long id);

    // Load order with full context: project + customer (for breadcrumb + PDF header).
    // Use case: generating order PDF or confirmation email.
    @Query("SELECT o FROM Order o WHERE o.id = :id")
    @EntityGraph(attributePaths = {"project", "project.customer"})
    Optional<Order> findByIdWithProjectAndCustomer(@Param("id") Long id);

    // ── Aggregate Queries (Finance/Reporting) ─────────────────────────────────
    //
    // WHY return BigDecimal and not a custom DTO here?
    // These are scalar queries — we only need one number, not an object.
    // Returning the primitive result directly is the simplest approach.
    //
    // WHY COALESCE(SUM(...), 0)?
    // SUM() returns NULL when there are no matching rows (empty set, no orders in period).
    // Without COALESCE: the Optional<BigDecimal> would be Optional.empty() — caller must handle null.
    // With COALESCE: always returns a number, even for empty periods (0.00).
    // This is a common SQL pattern — always defensive with aggregate functions.

    @Query("""
            SELECT COALESCE(SUM(o.totalAmount), 0)
            FROM Order o
            WHERE o.status NOT IN ('CANCELLED', 'DRAFT')
            AND o.startDate >= :from
            AND o.startDate <= :to
            """)
    BigDecimal sumRevenueForPeriod(
            @Param("from") LocalDate from,
            @Param("to") LocalDate to
    );

    // ── Bulk Status Update ────────────────────────────────────────────────────
    //
    // Use case: nightly job marks all CONFIRMED orders that started today as IN_PROGRESS.
    //
    // WHY @Modifying + @Query instead of loading entities and updating each?
    //
    // WITHOUT @Modifying (the naive approach):
    //   List<Order> orders = orderRepo.findByStatusAndStartDate(CONFIRMED, today);  // SELECT N rows
    //   orders.forEach(o -> o.setStatus(IN_PROGRESS));                              // N dirty-checks
    //   orderRepo.saveAll(orders);                                                   // N UPDATE queries
    //   TOTAL: 1 SELECT + N UPDATEs (e.g. 500 SELECTs + 500 UPDATEs = 1001 queries)
    //
    // WITH @Modifying:
    //   UPDATE orders SET status = 'IN_PROGRESS'
    //   WHERE status = 'CONFIRMED' AND start_date = ?
    //   TOTAL: 1 UPDATE — regardless of how many rows match!
    //
    // clearAutomatically=true: clears the Hibernate first-level cache after the bulk update.
    // WITHOUT this: entities already in the current session still have the OLD status in memory.
    // Hibernate's cache is now STALE — subsequent reads return wrong data.
    // clearAutomatically=true forces Hibernate to re-read from DB on next access.
    //
    // flushAutomatically=true: flushes pending changes to DB before executing this query.
    // Prevents: "I just set order.status = X in memory, then this bulk UPDATE overwrites it."

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Order o SET o.status = 'IN_PROGRESS'
            WHERE o.status = 'CONFIRMED'
            AND o.startDate = :today
            """)
    int markOrdersAsInProgress(@Param("today") LocalDate today);

    // ── Count Queries ─────────────────────────────────────────────────────────

    long countByProjectId(Long projectId);

    long countByStatus(OrderStatus status);
}