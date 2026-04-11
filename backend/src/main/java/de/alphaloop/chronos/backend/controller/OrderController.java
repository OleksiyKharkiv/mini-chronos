package de.alphaloop.chronos.backend.controller;

import de.alphaloop.chronos.backend.domain.Order;
import de.alphaloop.chronos.backend.dto.request.OrderCreateRequest;
import de.alphaloop.chronos.backend.dto.response.OrderDetailResponse;
import de.alphaloop.chronos.backend.dto.response.OrderResponse;
import de.alphaloop.chronos.backend.enums.OrderStatus;
import de.alphaloop.chronos.backend.mapper.OrderMapper;
import de.alphaloop.chronos.backend.service.OrderService;
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
 * OrderController — REST API for Order (Auftrag) management.
 *
 * This controller demonstrates the most important REST pattern in ERP systems:
 * SEPARATING DATA OPERATIONS FROM DOMAIN ACTIONS.
 *
 * DATA OPERATIONS (CRUD):
 *   POST   /api/orders           → create a draft order (just data)
 *   GET    /api/orders/{id}      → fetch order details
 *   GET    /api/orders           → list/search orders
 *
 * DOMAIN ACTIONS (business transitions):
 *   POST   /api/orders/{id}/confirm → confirm the draft (reserves equipment)
 *   POST   /api/orders/{id}/cancel  → cancel the order (releases equipment)
 *
 * WHY POST for confirm/cancel, not PUT or PATCH?
 *
 * PUT  = "replace the resource with this new state"
 *        → implies idempotency (running twice → same result)
 * PATCH = "update specific fields of the resource"
 *        → also implies the client knows what changed
 * POST = "execute this action" or "create a new resource"
 *        → no idempotency guarantee
 *
 * confirm() is NOT idempotent:
 *   First call:  DRAFT → CONFIRMED → creates Availability records
 *   Second call: already CONFIRMED → BusinessRuleException (not the same result!)
 *
 * cancel() is also NOT idempotent:
 *   First call:  CONFIRMED → CANCELLED → releases equipment
 *   Second call: already CANCELLED → BusinessRuleException
 *
 * POST is the correct HTTP verb for non-idempotent state transitions.
 *
 * Some APIs use PATCH /api/orders/{id}/status with body {"status": "CONFIRMED"}.
 * This looks similar to our ProjectController status endpoint.
 * We use explicit sub-resource actions (/confirm, /cancel) for Orders because:
 *   1. confirm() does MORE than set a field — it creates Availability records
 *   2. The action name communicates intent better than a generic status change
 *   3. Each action can have its own request body if needed later
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;
    private final OrderMapper  orderMapper;

    // ── READ ENDPOINTS ────────────────────────────────────────────────────────

    /**
     * Order detail — loads order with all items and equipment.
     * Uses EntityGraph ["items", "items.equipment"] → one query.
     *
     * Returns OrderDetailResponse which includes:
     *   - version field (for optimistic locking on updates)
     *   - items list with equipment details
     *   - customer name (for breadcrumb)
     */
    @GetMapping("/{id}")
    public ResponseEntity<OrderDetailResponse> getById(@PathVariable Long id) {
        Order order = orderService.getByIdWithItems(id);
        return ResponseEntity.ok(orderMapper.toDetailResponse(order));
    }

    /**
     * Order list with filtering.
     *
     * Three filter modes:
     *   ?status=CONFIRMED                           → orders by status
     *   ?projectId=300                             → orders by project
     *   ?start=2026-04-13&end=2026-04-20           → orders overlapping a period
     *   (no params)                                → all orders (paginated)
     *
     * WHY one endpoint for all filters instead of separate endpoints?
     * The frontend order list has a toolbar with filter options.
     * One flexible endpoint supports all combinations without proliferating routes.
     *
     * For a production API, consider Spring Data Specifications or QueryDSL
     * for arbitrary filter combinations. For MVP: explicit if-else is fine.
     */
    @GetMapping
    public ResponseEntity<Page<OrderResponse>> list(
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end,
            @PageableDefault(size = 20, sort = "createdAt") Pageable pageable
    ) {
        Page<Order> page;

        if (status != null) {
            page = orderService.getByStatus(status, pageable);
        } else if (projectId != null) {
            page = orderService.getByProject(projectId, pageable);
        } else {
            // Default: all orders, sorted by createdAt desc
            page = orderService.getByProject(null, pageable);
        }

        return ResponseEntity.ok(page.map(orderMapper::toResponse));
    }

    /**
     * Orders overlapping a date range — logistics view.
     * Returns List (not Page): logistics needs all orders for a period to plan delivery.
     *
     * GET /api/orders/period?start=2026-04-13&end=2026-04-20
     */
    @GetMapping("/period")
    public ResponseEntity<List<OrderResponse>> getByPeriod(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end
    ) {
        List<Order> orders = orderService.getOrdersForPeriod(start, end);
        return ResponseEntity.ok(orderMapper.toResponseList(orders));
    }

    // ── WRITE ENDPOINTS ───────────────────────────────────────────────────────

    /**
     * Create a DRAFT order.
     *
     * At creation: no availability reserved, status=DRAFT.
     * Equipment availability is reserved only on /confirm.
     *
     * Request body contains the nested items list:
     * {
     *   "projectId": 300,
     *   "startDate": "2026-04-13",
     *   "endDate": "2026-04-14",
     *   "items": [
     *     {"equipmentId": 200, "quantity": 1},
     *     {"equipmentId": 201, "quantity": 1}
     *   ]
     * }
     *
     * @Valid on the request: validates the outer object AND (because of @Valid on
     * the items field) each element in the items list.
     *
     * Converting OrderCreateRequest.items to OrderService.OrderItemRequest:
     * The service has its own inner record (OrderService.OrderItemRequest) that it
     * expects. We map from the controller's request DTO to the service's type here.
     * This keeps the service decoupled from the HTTP layer's DTO classes.
     */
    @PostMapping
    public ResponseEntity<OrderDetailResponse> createDraft(
            @RequestBody @Valid OrderCreateRequest request
    ) {
        // Map controller DTOs → service inner records
        List<OrderService.OrderItemRequest> itemRequests = request.items().stream()
                .map(item -> new OrderService.OrderItemRequest(
                        item.equipmentId(),
                        item.quantity()
                ))
                .toList();

        Order saved = orderService.createDraft(
                request.projectId(),
                request.startDate(),
                request.endDate(),
                itemRequests
        );

        URI location = ServletUriComponentsBuilder
                .fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(saved.getId())
                .toUri();

        // Return detail response (includes items) immediately after creation.
        // The client needs the item IDs and generated order number right away.
        Order withItems = orderService.getByIdWithItems(saved.getId());
        return ResponseEntity.created(location).body(orderMapper.toDetailResponse(withItems));
    }

    // ── DOMAIN ACTION ENDPOINTS ───────────────────────────────────────────────

    /**
     * Confirm a DRAFT order — the central business operation.
     *
     * POST /api/orders/{id}/confirm
     *
     * What happens inside the service (in one @Transactional):
     *   1. Load order with items and equipment
     *   2. Validate status = DRAFT
     *   3. FOR EACH ITEM: double-check availability (race condition guard)
     *   4. Set status = CONFIRMED
     *   5. FOR EACH ITEM: create Availability record (lock the dates)
     *   6. Update equipment status to RENTED
     *   7. Recalculate total
     *   8. Save (triggers @Version increment)
     *
     * Possible responses:
     *   200 OK           → confirmed successfully
     *   404 Not Found    → order doesn't exist
     *   409 Conflict     → equipment already booked (between search and confirm)
     *   409 Conflict     → order was concurrently modified (@Version mismatch)
     *   422 Unprocessable→ order is not in DRAFT status
     *
     * The GlobalExceptionHandler converts service exceptions to the correct status codes.
     * The controller itself always returns 200 OK — error cases never reach the return statement.
     */
    @PostMapping("/{id}/confirm")
    public ResponseEntity<OrderDetailResponse> confirm(@PathVariable Long id) {
        Order confirmed = orderService.confirmOrder(id);
        // Re-load with items for the response (confirm() returns the order,
        // but items.equipment may need a fresh load after status updates).
        Order withItems = orderService.getByIdWithItems(confirmed.getId());
        return ResponseEntity.ok(orderMapper.toDetailResponse(withItems));
    }

    /**
     * Cancel an order — releases equipment, soft-cancels availability records.
     *
     * POST /api/orders/{id}/cancel
     *
     * What happens inside the service:
     *   1. Load order with items and equipment
     *   2. Validate cancellation allowed (not COMPLETED or INVOICED)
     *   3. Bulk soft-cancel all Availability records (one UPDATE query)
     *   4. Return each equipment to AVAILABLE status
     *   5. Set order status = CANCELLED
     *
     * Note: Availability records are SOFT-CANCELLED (status = CANCELLED),
     * not deleted. Audit trail preserved.
     *
     * Possible responses:
     *   200 OK           → cancelled successfully
     *   404 Not Found    → order doesn't exist
     *   422 Unprocessable→ order is COMPLETED or INVOICED (cannot cancel)
     */
    @PostMapping("/{id}/cancel")
    public ResponseEntity<OrderDetailResponse> cancel(@PathVariable Long id) {
        Order cancelled = orderService.cancelOrder(id);
        Order withItems = orderService.getByIdWithItems(cancelled.getId());
        return ResponseEntity.ok(orderMapper.toDetailResponse(withItems));
    }
}