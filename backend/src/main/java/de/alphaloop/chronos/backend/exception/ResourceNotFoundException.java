package de.alphaloop.chronos.backend.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * ResourceNotFoundException — entity not found by the given identifier.
 * <p>
 * Thrown by: every service when findById() returns empty Optional.
 * HTTP status: 404 Not Found.
 * <p>
 * WHY a custom exception instead of returning null or Optional from the service?
 * <p>
 * Option A — return null:
 *   Customer customer = customerService.findById(id); // could be null
 *   customer.getName(); // → NullPointerException somewhere later
 *   The error appears FAR from the cause — hard to debug.
 * <p>
 * Option B — return Optional<Customer> from service:
 *   Optional<Customer> opt = customerService.findById(id);
 *   // every controller must handle the empty case manually
 *   // code duplication: if (opt.isEmpty()) return ResponseEntity.notFound()...
 * <p>
 * Option C — throw exception (our approach):
 *   Customer customer = customerService.getById(id); // throws if not found
 *   customer.getName(); // always safe — if we got here, customer exists
 *   The @ControllerAdvice (GlobalExceptionHandler) catches it → 404 response.
 *   One place handles all "not found" cases in the entire application.
 *
 * @ResponseStatus: when Spring MVC renders an unhandled exception,
 * it uses this annotation as the HTTP status. Belt-and-suspenders with @ControllerAdvice.
 */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class ResourceNotFoundException extends RuntimeException {

    // Entity type and ID stored separately for structured error responses.
    // GlobalExceptionHandler can include these in the JSON error body:
    // {"error": "NOT_FOUND", "entity": "Customer", "id": 42}
    private final String entityName;
    private final Object entityId;

    public ResourceNotFoundException(String entityName, Object entityId) {
        super(entityName + " not found with id: " + entityId);
        this.entityName = entityName;
        this.entityId = entityId;
    }

    // Convenience factory methods — cleaner call sites in service layer.
    // customerService calls: ResourceNotFoundException.customer(id)
    // instead of: new ResourceNotFoundException("Customer", id)
    public static ResourceNotFoundException customer(Long id) {
        return new ResourceNotFoundException("Customer", id);
    }

    public static ResourceNotFoundException project(Long id) {
        return new ResourceNotFoundException("Project", id);
    }

    public static ResourceNotFoundException order(Long id) {
        return new ResourceNotFoundException("Order", id);
    }

    public static ResourceNotFoundException equipment(Long id) {
        return new ResourceNotFoundException("EquipmentUnit", id);
    }

    public static ResourceNotFoundException user(Long id) {
        return new ResourceNotFoundException("User", id);
    }

    public String getEntityName() { return entityName; }
    public Object getEntityId()   { return entityId; }
}