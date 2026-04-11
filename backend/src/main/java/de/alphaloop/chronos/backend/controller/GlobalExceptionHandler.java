package de.alphaloop.chronos.backend.controller;

import de.alphaloop.chronos.backend.exception.BusinessRuleException;
import de.alphaloop.chronos.backend.exception.ConflictException;
import de.alphaloop.chronos.backend.exception.OptimisticLockConflictException;
import de.alphaloop.chronos.backend.exception.ResourceNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * GlobalExceptionHandler — one place that handles ALL exceptions in the application.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * WHY @RestControllerAdvice?
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * Without this class: every unhandled exception becomes a 500 Internal Server Error
 * with Spring's default white-label error page. The client gets no useful information.
 *
 * @RestControllerAdvice = @ControllerAdvice + @ResponseBody.
 * It intercepts exceptions thrown from ANY @RestController in the application.
 * The methods annotated with @ExceptionHandler convert exceptions → HTTP responses.
 *
 * WHY NOT handle exceptions in each controller?
 * Code duplication: ResourceNotFoundException can be thrown from every controller.
 * If handled per-controller: 5 controllers × same try-catch = 5 identical blocks.
 * GlobalExceptionHandler: handled ONCE, applied everywhere.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * WHY ProblemDetail (RFC 9457)?
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * ProblemDetail is the STANDARD format for API error responses since Spring 6.
 * It replaces the old pattern of custom error response classes.
 *
 * Structure:
 * {
 *   "type":     "https://mini-chronos.de/errors/not-found",  ← URI identifying the error type
 *   "title":    "Resource Not Found",                         ← short human-readable title
 *   "status":   404,                                          ← HTTP status code
 *   "detail":   "Customer not found with id: 42",            ← specific message
 *   "instance": "/api/customers/42",                         ← the request URI (auto-set by Spring)
 *   "timestamp": "2026-04-11T12:00:00Z"                      ← custom property we add
 * }
 *
 * Benefits over custom error classes:
 *   - Standardized: API clients know what to expect (RFC 9457 is public spec)
 *   - No boilerplate: ProblemDetail is a Spring 6 class, ready to use
 *   - Extensible: setProperty() adds custom fields without a new class
 *
 * Spring 6 + Spring Boot 3 support ProblemDetail natively.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // ── 404 Not Found ─────────────────────────────────────────────────────────

    /**
     * Handles: customerService.getById(id) when customer doesn't exist.
     * Every service throws ResourceNotFoundException for missing entities.
     */
    @ExceptionHandler(ResourceNotFoundException.class)
    public ProblemDetail handleNotFound(ResourceNotFoundException ex) {
        // No log.error here — 404 is a normal client error, not a server problem.
        // Logging every 404 would flood the logs with noise.
        log.debug("Resource not found: {}", ex.getMessage());

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND,
                ex.getMessage()
        );
        problem.setType(URI.create("https://mini-chronos.de/errors/not-found"));
        problem.setTitle("Resource Not Found");
        problem.setProperty("entityName", ex.getEntityName());
        problem.setProperty("entityId",   ex.getEntityId());
        problem.setProperty("timestamp",  Instant.now().toString());
        return problem;
    }

    // ── 409 Conflict ──────────────────────────────────────────────────────────

    /**
     * Handles: duplicate email, equipment already booked, maintenance blocked.
     * ConflictException covers both data conflicts and scheduling conflicts.
     */
    @ExceptionHandler(ConflictException.class)
    public ProblemDetail handleConflict(ConflictException ex) {
        log.warn("Conflict: {}", ex.getMessage());

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT,
                ex.getMessage()
        );
        problem.setType(URI.create("https://mini-chronos.de/errors/conflict"));
        problem.setTitle("Conflict");
        problem.setProperty("timestamp", Instant.now().toString());
        return problem;
    }

    /**
     * Handles: two users confirmed the same order simultaneously.
     * The second user receives 409 with a message to reload and retry.
     */
    @ExceptionHandler(OptimisticLockConflictException.class)
    public ProblemDetail handleOptimisticLock(OptimisticLockConflictException ex) {
        log.warn("Optimistic lock conflict: {}", ex.getMessage());

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT,
                ex.getMessage()
        );
        problem.setType(URI.create("https://mini-chronos.de/errors/concurrent-modification"));
        problem.setTitle("Concurrent Modification");
        problem.setProperty("hint",      "Reload the resource and retry your operation");
        problem.setProperty("timestamp", Instant.now().toString());
        return problem;
    }

    // ── 422 Unprocessable Content ─────────────────────────────────────────────

    /**
     * Handles: invalid status transitions, terminal state modifications,
     * date range errors, business rule violations.
     */
    @ExceptionHandler(BusinessRuleException.class)
    public ProblemDetail handleBusinessRule(BusinessRuleException ex) {
        log.warn("Business rule violation: {}", ex.getMessage());

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_ENTITY,
                ex.getMessage()
        );
        problem.setType(URI.create("https://mini-chronos.de/errors/business-rule"));
        problem.setTitle("Business Rule Violation");
        problem.setProperty("timestamp", Instant.now().toString());
        return problem;
    }

    // ── 400 Bad Request (validation) ──────────────────────────────────────────

    /**
     * Handles: @Valid fails on @RequestBody (missing required fields, wrong formats).
     *
     * MethodArgumentNotValidException is thrown by Spring MVC when
     * @Valid annotation fails on a request body.
     *
     * We collect ALL field errors into a map so the client knows EXACTLY
     * which fields are wrong in ONE response — not one error at a time.
     *
     * Example response:
     * {
     *   "title": "Validation Failed",
     *   "status": 400,
     *   "detail": "Request contains invalid fields",
     *   "fieldErrors": {
     *     "email": "Email must be a valid email address",
     *     "name": "Customer name is required"
     *   }
     * }
     *
     * WHY collect all errors at once?
     * If we return only the first error: the client fixes it, resubmits,
     * gets the second error, fixes it, resubmits... a bad UX loop.
     * All errors at once: one round trip to fix everything.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        // Collect field name → error message pairs
        Map<String, String> fieldErrors = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .collect(Collectors.toMap(
                        FieldError::getField,
                        fieldError -> fieldError.getDefaultMessage() != null
                                ? fieldError.getDefaultMessage()
                                : "Invalid value",
                        // If two errors on same field: keep both messages joined
                        (msg1, msg2) -> msg1 + "; " + msg2
                ));

        log.debug("Validation failed: {}", fieldErrors);

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "Request contains invalid fields"
        );
        problem.setType(URI.create("https://mini-chronos.de/errors/validation"));
        problem.setTitle("Validation Failed");
        problem.setProperty("fieldErrors", fieldErrors);
        problem.setProperty("timestamp",   Instant.now().toString());
        return problem;
    }

    // ── 500 Internal Server Error ─────────────────────────────────────────────

    /**
     * Catch-all: any exception not handled above becomes a 500.
     *
     * CRITICAL: do NOT expose the exception message to the client.
     * The message might contain: SQL, internal class names, stack traces,
     * database schema details — a security risk (information disclosure).
     *
     * We log the full exception (with stack trace) for internal debugging,
     * but return only a generic message to the client.
     *
     * In production: consider adding a correlation ID (UUID) to both
     * the log and the response so support can match a user complaint
     * to a specific log entry.
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleGeneric(Exception ex) {
        // log.error with the exception: prints the full stack trace to logs.
        // This is intentional — we WANT the full trace internally.
        log.error("Unexpected error: {}", ex.getMessage(), ex);

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred. Please contact support."
        );
        problem.setType(URI.create("https://mini-chronos.de/errors/internal"));
        problem.setTitle("Internal Server Error");
        problem.setProperty("timestamp", Instant.now().toString());
        return problem;
    }
}