package de.alphaloop.chronos.backend.controller;

import de.alphaloop.chronos.backend.domain.User;
import de.alphaloop.chronos.backend.dto.request.UserCreateRequest;
import de.alphaloop.chronos.backend.dto.response.UserResponse;
import de.alphaloop.chronos.backend.enums.RoleType;
import de.alphaloop.chronos.backend.mapper.UserMapper;
import de.alphaloop.chronos.backend.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;

/**
 * UserController — admin API for user and role management.
 *
 * In a real Chronos system this would be protected by:
 *   @PreAuthorize("hasRole('ADMIN')")
 * so only users with ADMIN role can access these endpoints.
 * Spring Security method-level security — topic for the next iteration.
 *
 * Notably absent: a "login" endpoint.
 * Authentication (POST /api/auth/login) lives in a separate AuthController
 * that integrates with Spring Security's authentication mechanism.
 * That's outside the scope of this MVP's domain layer focus.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;
    private final UserMapper  userMapper;

    @GetMapping("/{id}")
    public ResponseEntity<UserResponse> getById(@PathVariable Long id) {
        User user = userService.getById(id);
        return ResponseEntity.ok(userMapper.toResponse(user));
    }

    /**
     * Create a new system user.
     * Only admins should access this endpoint (@PreAuthorize in production).
     */
    @PostMapping
    public ResponseEntity<UserResponse> create(
            @RequestBody @Valid UserCreateRequest request
    ) {
        User saved = userService.createUser(
                request.username(),
                request.email(),
                request.password(),   // hashed immediately in UserService
                request.initialRole()
        );

        URI location = ServletUriComponentsBuilder
                .fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(saved.getId())
                .toUri();

        return ResponseEntity.created(location).body(userMapper.toResponse(saved));
    }

    /**
     * Assign an additional role to a user.
     * PATCH /api/users/{id}/roles/{roleType}
     *
     * WHY PATCH and not POST?
     * We're modifying an existing user resource (adding a role to their role list).
     * PATCH = partial modification of an existing resource.
     * (POST /api/users/{id}/roles would also be valid — it creates a role record.)
     * Both conventions exist in real projects. We use PATCH for consistency
     * with the status-transition pattern.
     */
    @PatchMapping("/{id}/roles/{roleType}")
    public ResponseEntity<UserResponse> assignRole(
            @PathVariable Long id,
            @PathVariable RoleType roleType
    ) {
        userService.assignRole(id, roleType);
        // Reload the user to get the updated roles list in the response.
        User updated = userService.getById(id);
        return ResponseEntity.ok(userMapper.toResponse(updated));
    }

    /**
     * Remove a role from a user.
     * DELETE /api/users/{id}/roles/{roleType}
     *
     * DELETE on a sub-resource (the specific role assignment).
     * Returns 204 No Content — the role is gone, nothing to return.
     *
     * Note: UserService.removeRole() prevents removing the last role.
     * That exception becomes 422 via GlobalExceptionHandler.
     */
    @DeleteMapping("/{id}/roles/{roleType}")
    public ResponseEntity<Void> removeRole(
            @PathVariable Long id,
            @PathVariable RoleType roleType
    ) {
        userService.removeRole(id, roleType);
        return ResponseEntity.noContent().build();
    }

    /**
     * Deactivate a user account.
     * DELETE /api/users/{id}
     * → soft-delete (active = false), not a hard DELETE.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deactivate(@PathVariable Long id) {
        userService.deactivate(id);
        return ResponseEntity.noContent().build();
    }
}