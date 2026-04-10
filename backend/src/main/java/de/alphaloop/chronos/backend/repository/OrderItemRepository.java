package de.alphaloop.chronos.backend.repository;

import de.alphaloop.chronos.backend.domain.OrderItem;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for OrderItem entity.
 *
 * OrderItem is rarely queried in isolation — it is usually accessed via Order:
 *   order.getItems()  → loaded through Order aggregate
 *
 * However, some use cases require direct OrderItem queries:
 *   - Logistics: "which items ship this week?" (cross-order view)
 *   - Equipment history: "all orders that ever included this projector"
 *   - Availability: finding the OrderItem linked to an Availability record
 *
 * IMPORTANT DESIGN NOTE:
 * OrderItem is part of the Order aggregate (orphanRemoval = true in Order).
 * This means you should NEVER call orderItemRepository.delete(item) directly.
 * The correct way: order.removeItem(item) → orderRepository.save(order)
 * Let the aggregate root manage its children's lifecycle.
 * This repository provides READ operations + a few specific write helpers.
 */
@Repository
public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {

    // ── Items by Order ────────────────────────────────────────────────────────
    //
    // Use case: when you have only the orderId (e.g. from a URL parameter)
    // and need the items without loading the whole Order object.
    //
    // WHY not just order.getItems()?
    // You need an active Hibernate session to trigger LAZY loading.
    // If the Order was loaded in a previous transaction, the session is closed.
    // Direct repository query opens a fresh session.
    //
    // The EntityGraph here loads equipment to avoid N+1 when rendering items table:
    // item name (equipment.name) would trigger a separate SELECT per item otherwise.

    @EntityGraph(attributePaths = {"equipment"})
    List<OrderItem> findByOrderId(Long orderId);

    // ── Equipment History ─────────────────────────────────────────────────────
    //
    // Use case: "show the rental history for this projector"
    // This is the backwards traversal: Equipment → OrderItems → Orders
    //
    // Loading order and order.project.customer for full history display:
    //   "Epson #3 was rented for: Jahreskonferenz 2026 (Lang GmbH) [13.04 - 14.04]"
    //
    // Multiple nested paths in EntityGraph:
    //   "order" → load the Order
    //   "order.project" → load the Project that the Order belongs to
    //   "order.project.customer" → load the Customer of that Project
    //
    // This generates one LEFT JOIN chain, not three separate queries.

    @Query("SELECT oi FROM OrderItem oi WHERE oi.equipment.id = :equipmentId ORDER BY oi.createdAt DESC")
    @EntityGraph(attributePaths = {"order", "order.project", "order.project.customer"})
    List<OrderItem> findByEquipmentIdWithOrderHistory(@Param("equipmentId") Long equipmentId);

    // ── Availability Link ─────────────────────────────────────────────────────
    //
    // Use case: when cancelling an Availability record, find its linked OrderItem
    // to update the order total or remove the item if needed.
    //
    // This traverses the reverse direction of Availability.orderItem:
    //   Availability.orderItem → OrderItem (ManyToOne in Availability)
    //   Here we query from the OrderItem side: "find the OrderItem that has
    //   an Availability with this ID."
    //
    // WHY this query instead of using AvailabilityRepository?
    // Sometimes the service layer has availabilityId and needs the OrderItem
    // without loading Availability first — one less query in the flow.

    @Query("""
            SELECT oi FROM OrderItem oi
            JOIN Availability a ON a.orderItem.id = oi.id
            WHERE a.id = :availabilityId
            """)
    Optional<OrderItem> findByAvailabilityId(@Param("availabilityId") Long availabilityId);

    // ── Existence Checks ──────────────────────────────────────────────────────
    //
    // Use case: before deleting equipment, check if it was ever ordered.
    // If true → soft delete (mark as RETIRED), not hard delete.
    // This protects financial history: you can't delete equipment that appeared in invoices.

    boolean existsByEquipmentId(Long equipmentId);
}