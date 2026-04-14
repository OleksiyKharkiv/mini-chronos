package de.alphaloop.chronos.backend.controller;

import de.alphaloop.chronos.backend.domain.Customer;
import de.alphaloop.chronos.backend.dto.request.CustomerCreateRequest;
import de.alphaloop.chronos.backend.dto.request.CustomerUpdateRequest;
import de.alphaloop.chronos.backend.dto.response.CustomerDetailResponse;
import de.alphaloop.chronos.backend.dto.response.CustomerResponse;
import de.alphaloop.chronos.backend.mapper.CustomerMapper;
import de.alphaloop.chronos.backend.service.CustomerService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * CustomerController — REST API for Customer (Kunden) management.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * ARCHITECTURE: THE CONTROLLER'S ONLY JOB
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * A controller does EXACTLY three things, nothing more:
 *   1. Parse/validate the incoming HTTP request
 *   2. Delegate to the service
 *   3. Map the result to a response DTO and return it with the correct HTTP status
 *
 * The controller does NOT:
 *   - Contain business logic (that's the service's job)
 *   - Access the database directly (that's the repository's job)
 *   - Construct domain objects from scratch (that's the mapper's job)
 *
 * If a controller method is longer than ~15 lines, something is wrong —
 * business logic has leaked into the wrong layer.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * @RestController vs @Controller
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * @Controller returns view names (for Thymeleaf/JSP — server-side HTML rendering).
 * @RestController = @Controller + @ResponseBody on every method.
 * @ResponseBody tells Spring: serialize the return value to JSON directly,
 * do NOT look for a view template.
 * For a REST API (our case): always @RestController.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * @RequestMapping("/api/customers") at class level
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * All methods in this controller inherit the "/api/customers" prefix.
 * Method-level @GetMapping, @PostMapping etc. add only the suffix.
 * Result: GET /api/customers, GET /api/customers/{id}, POST /api/customers, etc.
 *
 * The "/api/" prefix separates our REST endpoints from potential static resources
 * or other routes. In production, NGINX proxies /api/* to the Spring Boot app.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/customers")
public class CustomerController {

    private final CustomerService customerService;
    private final CustomerMapper  customerMapper;

    // ── GET /api/customers ────────────────────────────────────────────────────

    /**
     * Paginated list of active customers with optional search.
     *
     * Query parameters:
     *   ?search=Lang         → filter by name containing "Lang"
     *   ?activeOnly=false    → include inactive customers (admin use)
     *   ?page=0&size=20      → pagination
     *   ?sort=name,asc       → sorting
     *
     * @PageableDefault(size = 20, sort = "name"):
     * If the client sends no pagination params, use these defaults.
     * Without @PageableDefault: Spring would default to page=0, size=20, unsorted.
     * Explicit default is better — sort="name" gives predictable alphabetical order.
     *
     * WHY return Page<CustomerResponse> and not List<CustomerResponse>?
     * Page includes: content (the items), totalElements, totalPages, currentPage.
     * The frontend needs totalElements to render the pagination control:
     * "Showing 1-20 of 347 customers". A List has no count.
     *
     * Page.map(): transforms Page<Customer> → Page<CustomerResponse>
     * without losing pagination metadata. map() applies the function
     * to each element, keeping totalElements/totalPages intact.
     */
    @GetMapping
    public ResponseEntity<Page<CustomerResponse>> list(
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "true") boolean activeOnly,
            @PageableDefault(size = 20, sort = "name") Pageable pageable
    ) {
        Page<Customer> page = (search != null && !search.isBlank())
                ? customerService.searchCustomers(search, activeOnly, pageable)
                : customerService.getActiveCustomers(pageable);

        return ResponseEntity.ok(page.map(customerMapper::toResponse));
    }

    // ── GET /api/customers/{id} ───────────────────────────────────────────────

    /**
     * Customer detail with project list.
     *
     * Uses getByIdWithProjects() → EntityGraph loads customer + projects in one query.
     * Returns CustomerDetailResponse (contains projects list).
     *
     * WHY not use the same getById() as in the list?
     * getById() returns Customer without projects (LAZY — not loaded).
     * Calling customerMapper.toDetailResponse() on that would trigger
     * LazyInitializationException when MapStruct accesses customer.getProjects().
     * The explicit "WithProjects" variant ensures the data is loaded.
     *
     * This is the golden rule: match the service method to the response DTO.
     * Detail DTO (with collections) → use the "WithXxx" service method.
     * List DTO (without collections) → use the basic service method.
     */
    @GetMapping("/{id}")
    public ResponseEntity<CustomerDetailResponse> getById(@PathVariable Long id) {
        Customer customer = customerService.getByIdWithProjects(id);
        return ResponseEntity.ok(customerMapper.toDetailResponse(customer));
    }

    // ── POST /api/customers ───────────────────────────────────────────────────

    /**
     * Create a new customer.
     *
     * @RequestBody @Valid CustomerCreateRequest request:
     *   @RequestBody: Jackson deserializes the JSON body to CustomerCreateRequest.
     *   @Valid: triggers Jakarta Validation on the request.
     *     If validation fails (missing name, invalid email, etc.):
     *     Spring throws MethodArgumentNotValidException BEFORE our code runs.
     *     GlobalExceptionHandler catches it → 400 Bad Request with field errors.
     *     Our method body only executes for VALID requests.
     *
     * HttpStatus.CREATED (201):
     *   201 Created is more precise than 200 OK for resource creation.
     *   REST convention: POST that creates a resource → 201.
     *   The Location header (pointing to the new resource) is optional
     *   but professional. We include it here.
     *
     * ResponseEntity.created(location).body(response):
     *   Sets status 201 + Location header + body in one call.
     */
    @PostMapping
    public ResponseEntity<CustomerResponse> create(
            @RequestBody @Valid CustomerCreateRequest request
    ) {
        Customer customer  = customerMapper.toEntity(request);
        Customer saved     = customerService.create(customer);
        CustomerResponse response = customerMapper.toResponse(saved);

        // Location header: tells the client WHERE to find the created resource.
        // e.g. Location: http://localhost:8080/api/customers/42
        var location = org.springframework.web.servlet.support.ServletUriComponentsBuilder
                .fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(saved.getId())
                .toUri();

        return ResponseEntity.created(location).body(response);
    }

    // ── PUT /api/customers/{id} ───────────────────────────────────────────────

    /**
     * Update customer data.
     *
     * PUT semantics: replace the entire resource with the provided data.
     * (PATCH would mean partial update — only send changed fields.)
     * We use PUT here for simplicity — the client always sends all fields.
     *
     * The mapper's updateEntity() mutates the existing entity IN-PLACE.
     * No new object created — Hibernate's dirty checking detects changes.
     * The service.update() validates email uniqueness and saves.
     */
    @PutMapping("/{id}")
    public ResponseEntity<CustomerResponse> update(
            @PathVariable Long id,
            @RequestBody @Valid CustomerUpdateRequest request
    ) {
        Customer existing = customerService.getById(id);
        customerMapper.updateEntity(request, existing);
        Customer updated  = customerService.update(id, existing);
        return ResponseEntity.ok(customerMapper.toResponse(updated));
    }

    // ── DELETE /api/customers/{id} ────────────────────────────────────────────

    /**
     * Soft-delete (deactivate) a customer.
     *
     * HTTP DELETE on a soft-delete resource: debatable convention.
     * Some APIs use DELETE → sets active=false (our approach).
     * Others use PATCH /api/customers/{id}/deactivate.
     * Both are valid — consistency within the project matters more than choice.
     *
     * 204 No Content: DELETE that succeeds returns no body.
     * REST convention: successful DELETE → 204 (not 200 with a body).
     * ResponseEntity<Void>: explicitly no response body.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deactivate(@PathVariable Long id) {
        customerService.deactivate(id);
        return ResponseEntity.noContent().build();
    }
}