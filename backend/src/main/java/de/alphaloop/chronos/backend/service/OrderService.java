package de.alphaloop.chronos.backend.service;

import de.alphaloop.chronos.backend.domain.Availability;
import de.alphaloop.chronos.backend.domain.EquipmentUnit;
import de.alphaloop.chronos.backend.domain.Order;
import de.alphaloop.chronos.backend.domain.OrderItem;
import de.alphaloop.chronos.backend.domain.Project;
import de.alphaloop.chronos.backend.enums.OrderStatus;
import de.alphaloop.chronos.backend.exception.BusinessRuleException;
import de.alphaloop.chronos.backend.exception.ConflictException;
import de.alphaloop.chronos.backend.exception.OptimisticLockConflictException;
import de.alphaloop.chronos.backend.exception.ResourceNotFoundException;
import de.alphaloop.chronos.backend.repository.AvailabilityRepository;
import de.alphaloop.chronos.backend.repository.OrderRepository;
import jakarta.persistence.OptimisticLockException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * OrderService — the heart of mini-chronos.
 *
 * This is the most complex service because an Order ties together
 * all other domain objects: Project, Customer, EquipmentUnit, Availability.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * KEY CONCEPTS DEMONSTRATED IN THIS SERVICE:
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * 1. TRANSACTION BOUNDARIES:
 *    Every @Transactional method is ONE atomic unit of work.
 *    If any step fails, ALL database changes in that method roll back.
 *    This is how rental systems maintain consistency.
 *
 * 2. OPTIMISTIC LOCKING:
 *    Order has @Version. When two users modify the same order simultaneously,
 *    one succeeds and one gets OptimisticLockException.
 *    We catch it and throw a domain-level exception with a clear message.
 *
 * 3. DOUBLE-CHECK AVAILABILITY:
 *    The confirmOrder() method re-checks availability INSIDE the transaction.
 *    This prevents the race condition where two users book the same equipment.
 *
 * 4. AGGREGATE ROOT PATTERN:
 *    Order is the aggregate root for OrderItems.
 *    We NEVER save OrderItem directly — always through Order.
 *    The service uses order.addItem() / order.removeItem() + orderRepository.save(order).
 *
 * 5. RICH DOMAIN MODEL:
 *    Business rules live in entities (order.confirm(), order.cancel()).
 *    The service coordinates: load → delegate to domain → save.
 *    It does NOT contain if-statements that belong in the entity.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrderService {

    private final OrderRepository orderRepository;
    private final AvailabilityRepository availabilityRepository;
    private final ProjectService projectService;
    private final EquipmentService equipmentService;

    // ── READ OPERATIONS ───────────────────────────────────────────────────────

    /**
     * Order detail: loads order + all items + each item's equipment.
     * One query via nested EntityGraph ["items", "items.equipment"].
     * Without this: 1 + 1 + N queries (N = number of items).
     */
    public Order getByIdWithItems(Long id) {
        return orderRepository.findByIdWithItems(id)
                .orElseThrow(() -> ResourceNotFoundException.order(id));
    }

    /**
     * For PDF/email generation: needs customer name and project details.
     * Different EntityGraph than getByIdWithItems() — loads different associations.
     */
    public Order getByIdWithProjectAndCustomer(Long id) {
        return orderRepository.findByIdWithProjectAndCustomer(id)
                .orElseThrow(() -> ResourceNotFoundException.order(id));
    }

    public Page<Order> getByProject(Long projectId, Pageable pageable) {
        return orderRepository.findByProjectId(projectId, pageable);
    }

    public Page<Order> getByStatus(OrderStatus status, Pageable pageable) {
        return orderRepository.findByStatus(status, pageable);
    }

    /**
     * Logistics view: all orders starting/ending in a given week.
     * Returns List (not Page) — logistics needs ALL orders for planning.
     */
    public List<Order> getOrdersForPeriod(LocalDate start, LocalDate end) {
        return orderRepository.findActiveOrdersOverlappingPeriod(start, end);
    }

    // ── WRITE OPERATIONS ──────────────────────────────────────────────────────

    /**
     * Create a new DRAFT order.
     *
     * At this stage: NO availability is reserved yet.
     * The order is just a placeholder — a DRAFT.
     * Availability is reserved only when the order is CONFIRMED (see confirmOrder()).
     *
     * WHY separate create (DRAFT) and confirm (reserve availability)?
     *
     * Because the user workflow is:
     *   1. Create draft (fill in dates, select equipment)
     *   2. Review the draft
     *   3. Confirm (locks the equipment for those dates)
     *
     * If we reserved availability on create:
     *   - Abandoned drafts would block equipment for other customers
     *   - Sales reps who "just explore options" would block inventory
     *
     * The DRAFT → CONFIRMED transition is the moment equipment is actually committed.
     *
     * TRANSACTION:
     * One @Transactional for: load project + validate + save order + save items.
     * All atomic — if saving items fails, the order header is also rolled back.
     */
    @Transactional
    public Order createDraft(Long projectId, LocalDate startDate, LocalDate endDate,
                             List<OrderItemRequest> itemRequests) {

        log.info("Creating draft order: projectId={}, {} to {}", projectId, startDate, endDate);

        // Validate the project exists and is in a state that accepts orders.
        Project project = projectService.getById(projectId);
        validateProjectAcceptsOrders(project);

        // Validate the date range itself.
        if (!endDate.isAfter(startDate) && !endDate.isEqual(startDate)) {
            throw new BusinessRuleException("Order end date must be on or after start date");
        }

        // Build the Order aggregate.
        Order order = new Order();
        order.setProject(project);
        order.setStartDate(startDate);
        order.setEndDate(endDate);
        order.setStatus(OrderStatus.DRAFT);

        // Add items. Note: order not yet saved, but Cascade.ALL will save items with it.
        for (OrderItemRequest req : itemRequests) {
            EquipmentUnit equipment = equipmentService.getById(req.equipmentId());
            // Factory method: creates item AND captures the price snapshot.
            // unitPrice = equipment.getDailyRate() AT THIS MOMENT.
            OrderItem item = OrderItem.of(order, equipment, req.quantity());
            order.addItem(item);  // BUG FIX: must be addItem which calls item.setOrder(this)
        }

        // Recalculate total from items before saving.
        order.recalculateTotalAmount();

        // CascadeType.ALL on Order.items: save(order) also saves all items.
        // No separate orderItemRepository.saveAll() needed.
        Order saved = orderRepository.save(order);
        log.info("Draft order created: id={}, orderNumber={}", saved.getId(), saved.getOrderNumber());
        return saved;
    }

    /**
     * Confirm a DRAFT order — the most critical and complex operation.
     *
     * This method does the following in ONE atomic transaction:
     *   1. Load the order with a pessimistic read (or rely on @Version)
     *   2. Validate the order is in DRAFT status
     *   3. For EACH item: check availability (SECOND CHECK — race condition guard)
     *   4. Confirm the order (status DRAFT → CONFIRMED)
     *   5. For EACH item: create Availability record (lock the dates)
     *   6. Update equipment status to RENTED
     *   7. Recalculate and save total
     *
     * ─────────────────────────────────────────────────────────────────────────
     * WHY IS THE SECOND CHECK (step 3) CRITICAL?
     * ─────────────────────────────────────────────────────────────────────────
     *
     * The race condition scenario:
     *
     * T=0: Maria searches → Epson#1 appears AVAILABLE
     * T=0: Klaus  searches → Epson#1 appears AVAILABLE
     * T=1: Maria  clicks "Confirm" → this method starts, transaction begins
     * T=1: Klaus  clicks "Confirm" → this method starts, another transaction begins
     * T=2: Maria's transaction: step 3 checks → AVAILABLE ✓ → step 5 creates Availability
     * T=2: Klaus's transaction: step 3 checks → NOT AVAILABLE ✗ → throws ConflictException
     * T=3: Maria's transaction commits → Epson#1 is RESERVED
     * T=3: Klaus's transaction rolls back → no Availability created
     *
     * Without step 3 (second check):
     * T=2: Maria creates Availability(Epson#1, April13-14, RESERVED) → committed
     * T=2: Klaus creates Availability(Epson#1, April13-14, RESERVED) → ALSO committed!
     * Result: two overlapping Availability records → double-booked equipment → chaos
     *
     * The second check INSIDE the transaction, combined with database isolation,
     * prevents this. Both transactions cannot see each other's uncommitted writes.
     *
     * ─────────────────────────────────────────────────────────────────────────
     * OPTIMISTIC LOCKING (the @Version field on Order):
     * ─────────────────────────────────────────────────────────────────────────
     *
     * Scenario: two users confirm the same DRAFT order simultaneously.
     * (E.g. a bug in the UI allowed double-click, or two browser tabs.)
     *
     * T=0: User A reads Order (version=0)
     * T=0: User B reads Order (version=0)
     * T=1: User A confirms → UPDATE orders SET status='CONFIRMED', version=1 WHERE id=X AND version=0
     *      → 1 row updated ✓ — commits
     * T=1: User B confirms → UPDATE orders SET status='CONFIRMED', version=1 WHERE id=X AND version=0
     *      → 0 rows updated! (version is now 1, not 0)
     *      → Hibernate throws OptimisticLockException
     *      → We catch it → throw OptimisticLockConflictException → 409 response
     *
     * Without @Version: both confirms would succeed → double availability creation.
     */
    @Transactional
    public Order confirmOrder(Long orderId) {
        log.info("Confirming order: id={}", orderId);

        // Load with items AND each item's equipment in one query.
        // We need item.getEquipment() for availability check and status update.
        Order order = orderRepository.findByIdWithItems(orderId)
                .orElseThrow(() -> ResourceNotFoundException.order(orderId));

        // The entity enforces the business rule: only DRAFT can be confirmed.
        // This throws IllegalStateException if status != DRAFT.
        try {
            order.confirm(); // → status becomes CONFIRMED, @Version will increment
        } catch (IllegalStateException e) {
            throw new BusinessRuleException(e.getMessage());
        }

        // SECOND CHECK: for each item, verify availability inside this transaction.
        // This is the race-condition guard described above.
        for (OrderItem item : order.getItems()) {
            EquipmentUnit equipment = item.getEquipment();
            boolean available = equipmentService.isAvailable(
                    equipment.getId(), order.getStartDate(), order.getEndDate()
            );
            if (!available) {
                // Roll back the entire transaction — the order stays DRAFT.
                throw ConflictException.equipmentNotAvailable(
                        equipment.getSku(),
                        order.getStartDate().toString(),
                        order.getEndDate().toString()
                );
            }
        }

        // All equipment is available — create Availability records (the actual locks).
        for (OrderItem item : order.getItems()) {
            EquipmentUnit equipment = item.getEquipment();

            // Factory method from Availability domain class.
            Availability reservation = Availability.reserveForOrderItem(
                    equipment,
                    item,
                    order.getStartDate(),
                    order.getEndDate()
            );
            availabilityRepository.save(reservation);

            // Update equipment status to RENTED.
            // This is a denormalized status — it can be derived from Availability,
            // but storing it directly avoids a COUNT query on every equipment list render.
            equipment.markAsRented();
        }

        order.recalculateTotalAmount();

        // Explicit save() here for clarity — the order's status changed,
        // plus we want to trigger the optimistic lock check NOW,
        // not at the end of the transaction (when it might be too late to handle gracefully).
        try {
            Order saved = orderRepository.save(order);
            log.info("Order confirmed: id={}, orderNumber={}", saved.getId(), saved.getOrderNumber());
            return saved;
        } catch (OptimisticLockException | OptimisticLockingFailureException e) {
            // Another user confirmed or modified this order concurrently.
            throw new OptimisticLockConflictException("Order", orderId);
        }
    }

    /**
     * Cancel an order — reverts the availability locks.
     *
     * TRANSACTION FLOW:
     * 1. Load order
     * 2. Validate cancellation is allowed (entity enforces: not COMPLETED or INVOICED)
     * 3. Soft-cancel ALL availability records for this order (bulk UPDATE)
     * 4. Mark equipment as AVAILABLE again for each item
     * 5. Set order status to CANCELLED
     *
     * WHY soft-cancel Availability (set to CANCELLED) instead of DELETE?
     * Audit trail: if a client disputes "we never booked that weekend",
     * the CANCELLED availability record is evidence that they did book and then cancelled.
     * Financial audit requires this history to be preserved.
     *
     * The bulk UPDATE in step 3 is one SQL statement regardless of item count.
     * See AvailabilityRepository.cancelByOrderId() for the @Modifying explanation.
     */
    @Transactional
    public Order cancelOrder(Long orderId) {
        log.info("Cancelling order: id={}", orderId);

        Order order = orderRepository.findByIdWithItems(orderId)
                .orElseThrow(() -> ResourceNotFoundException.order(orderId));

        try {
            order.cancel();
        } catch (IllegalStateException e) {
            throw new BusinessRuleException(e.getMessage());
        }

        // Bulk soft-cancel all availability records for this order's items.
        // One UPDATE query regardless of how many items/availability records exist.
        int cancelledRecords = availabilityRepository.cancelByOrderId(orderId);
        log.debug("Cancelled {} availability records for order {}", cancelledRecords, orderId);

        // Return equipment to AVAILABLE status.
        for (OrderItem item : order.getItems()) {
            EquipmentUnit equipment = item.getEquipment();
            // markAsAvailable() only if currently RENTED — idempotent check.
            if (equipment.getStatus() == de.alphaloop.chronos.backend.enums.EquipmentStatus.RENTED) {
                equipment.markAsAvailable();
            }
        }

        log.info("Order cancelled: id={}", orderId);
        return order;
    }

    /**
     * Nightly batch job: mark all CONFIRMED orders that start today as IN_PROGRESS.
     *
     * This is a BULK OPERATION — uses @Modifying UPDATE in the repository.
     * One SQL UPDATE regardless of how many orders match.
     *
     * @return count of orders transitioned
     *
     * WHY @Transactional here too?
     * Even though it's a single bulk UPDATE, the @Transactional boundary
     * ensures the operation is atomic. If something fails after the UPDATE
     * (e.g. a post-processing step), the UPDATE rolls back.
     */
    @Transactional
    public int markStartingOrdersAsInProgress(LocalDate today) {
        log.info("Marking orders starting on {} as IN_PROGRESS", today);
        int count = orderRepository.markOrdersAsInProgress(today);
        log.info("Marked {} orders as IN_PROGRESS", count);
        return count;
    }

    // ── PRIVATE HELPERS ───────────────────────────────────────────────────────

    private void validateProjectAcceptsOrders(Project project) {
        if (project.getStatus() != de.alphaloop.chronos.backend.enums.ProjectStatus.ACTIVE) {
            throw new BusinessRuleException(
                    "Orders can only be created for ACTIVE projects. " +
                            "Current project status: " + project.getStatus()
            );
        }
    }

    // ── INNER RECORD: Request DTO for items ───────────────────────────────────

    /**
     * Inner record representing an item request when creating an order.
     *
     * WHY an inner record and not a separate DTO class?
     * This record is ONLY used as input for createDraft() in this service.
     * It doesn't need its own file — keeping it here reduces cognitive overhead.
     * When we add proper DTOs + MapStruct (next step), this moves to the DTO package.
     *
     * Java records (since Java 16):
     *   - Immutable (all fields final)
     *   - Auto-generated: constructor, getters, equals, hashCode, toString
     *   - Perfect for data transfer objects
     */
    public record OrderItemRequest(Long equipmentId, int quantity) {
        // Compact constructor (records feature): runs inside the auto-generated constructor.
        // Validates on construction — no invalid requests can be created.
        public OrderItemRequest {
            if (quantity < 1) {
                throw new IllegalArgumentException("Quantity must be at least 1");
            }
        }
    }
}