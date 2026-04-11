package de.alphaloop.chronos.backend.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * OptimisticLockConflictException — concurrent modification detected.
 * <p>
 * HTTP status: 409 Conflict.
 * <p>
 * Thrown when Hibernate's OptimisticLockException is caught in the service layer.
 * This happens when two users try to modify the same Order simultaneously:
 * <p>
 * Timeline:
 *   T=0: User A reads Order (version=3)
 *   T=0: User B reads Order (version=3)
 *   T=1: User A saves → UPDATE WHERE version=3 → success, version becomes 4
 *   T=1: User B saves → UPDATE WHERE version=3 → 0 rows updated (version is now 4!)
 *        → Hibernate throws OptimisticLockException
 *        → Service catches it, throws OptimisticLockConflictException
 *        → GlobalExceptionHandler returns 409 with a user-friendly message
 * <p>
 * WHY wrap OptimisticLockException instead of letting it propagate?
 * OptimisticLockException is a Hibernate/JPA exception.
 * If it propagates past the service layer, the controller would need to know
 * about Hibernate internals — a leaky abstraction.
 * Wrapping it in a domain-level exception keeps the service layer's API clean.
 * <p>
 * What should the client do on receiving this?
 * Re-fetch the resource (GET) to see the current state, then retry the operation.
 * The response body should contain: "The resource was modified by another user. Please refresh."
 */
@ResponseStatus(HttpStatus.CONFLICT)
public class OptimisticLockConflictException extends RuntimeException {

    public OptimisticLockConflictException(String entityName, Long entityId) {
        super(entityName + " (id=" + entityId + ") was modified concurrently. Please reload and retry.");
    }
}