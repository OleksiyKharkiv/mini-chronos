package de.alphaloop.chronos.backend.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * BusinessRuleException — a valid request violates a domain business rule.
 * <p>
 * HTTP status: 422 Unprocessable Entity.
 * <p>
 * The difference from ConflictException:
 *   ConflictException = "this conflicts with existing data" (409)
 *   BusinessRuleException = "this violates a business process rule" (422)
 * <p>
 * Examples:
 *   - Trying to confirm an order that is not in DRAFT status
 *   - Trying to cancel an already INVOICED order
 *   - Trying to transition a Project from COMPLETED → ACTIVE (terminal state)
 *   - Order endDate is before startDate
 * <p>
 * These are not data format errors (400) and not conflicts (409).
 * They are valid-looking operations that break the domain model's invariants.
 * <p>
 * WHY not just use IllegalStateException from the entity?
 * IllegalStateException is a Java internal exception — it carries no HTTP semantics.
 * BusinessRuleException is caught by GlobalExceptionHandler → 422 response with message.
 * The entity still throws IllegalStateException for unit tests (where no HTTP is involved).
 * The service layer converts it:
 *   try { order.confirm(); }
 *   catch (IllegalStateException e) { throw new BusinessRuleException(e.getMessage()); }
 */
@ResponseStatus(HttpStatus.UNPROCESSABLE_CONTENT)
public class BusinessRuleException extends RuntimeException {

    public BusinessRuleException(String message) {
        super(message);
    }
}