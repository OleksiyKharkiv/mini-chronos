package de.alphaloop.chronos.backend.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * ConflictException — a resource already exists or a scheduling conflict occurred.
 * <p>
 * HTTP status: 409 Conflict.
 * <p>
 * Two main use cases:
 * <p>
 * 1. Uniqueness conflict (duplicate data):
 *    - Trying to register a user with an already-existing email
 *    - Trying to create a Customer with a duplicate email
 *    These are data integrity issues — the client sent valid data,
 *    but it conflicts with existing data.
 * <p>
 * 2. Availability conflict (scheduling):
 *    - Equipment is already booked for the requested dates
 *    - Trying to schedule maintenance while equipment has active reservations
 *    These are business-level conflicts — two valid operations
 *    that cannot coexist simultaneously.
 * <p>
 * WHY 409 and not 400 (Bad Request)?
 * 400 means the request itself is malformed (invalid format, missing fields).
 * 409 means the request is well-formed, but conflicts with current server state.
 * The client could retry after the conflict is resolved (e.g. different dates).
 */
@ResponseStatus(HttpStatus.CONFLICT)
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }

    // Factory methods for common conflict scenarios
    public static ConflictException emailAlreadyExists(String email) {
        return new ConflictException("Email already registered: " + email);
    }

    public static ConflictException equipmentNotAvailable(String sku, String startDate, String endDate) {
        return new ConflictException(
                "Equipment '" + sku + "' is not available from " + startDate + " to " + endDate
        );
    }

    public static ConflictException maintenanceBlockedByReservation(String sku) {
        return new ConflictException(
                "Cannot schedule maintenance for '" + sku + "': active reservations exist in this period"
        );
    }
}