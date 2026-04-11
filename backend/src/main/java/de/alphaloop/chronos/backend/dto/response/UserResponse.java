package de.alphaloop.chronos.backend.dto.response;

import java.time.LocalDateTime;
import java.util.List;

/**
 * User response.
 *
 * CRITICAL: NO password_hash field here.
 * The password hash must NEVER leave the server.
 * Even a BCrypt hash can be subject to offline brute-force attacks.
 * Strict rule: password-related data is write-only.
 *
 * roles: List<String> not List<RoleType>.
 * WHY String? The role names go to the frontend as plain strings ("ADMIN", "SALES").
 * The frontend doesn't need the Java enum — just the string value.
 * Using String avoids any enum serialization configuration issues.
 */
public record UserResponse(
        Long id,
        String username,
        String email,
        String firstName,
        String lastName,
        String displayName,   // computed: firstName + " " + lastName (or username)
        boolean active,
        List<String> roles,   // e.g. ["SALES", "LOGISTICS"] — no password, no hash
        LocalDateTime createdAt
) {}