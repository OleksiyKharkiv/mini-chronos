package de.alphaloop.chronos.backend.repository;

import de.alphaloop.chronos.backend.domain.Role;
import de.alphaloop.chronos.backend.enums.RoleType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for Role entity.
 *
 * Role is managed through User (CascadeType.ALL, orphanRemoval = true on User.roles).
 * This means the primary way to add/remove roles is:
 *   user.addRole(Role.of(user, RoleType.SALES));
 *   userRepository.save(user);  // cascade saves the Role
 *
 * This repository provides READ operations and admin-level bulk operations
 * that bypass the aggregate (justified for admin use cases with many users).
 *
 * WHY have a RoleRepository at all if User manages roles?
 * Admin use cases that require querying across users:
 *   "Find all users with LOGISTICS role" → can't do this through User aggregate
 *   "Does this user already have this role?" → needed for idempotent role assignment
 *   "Remove all roles of type X from a user" → cleaner with a direct query
 */
@Repository
public interface RoleRepository extends JpaRepository<Role, Long> {

    // ── Lookup ────────────────────────────────────────────────────────────────

    List<Role> findByUserId(Long userId);

    Optional<Role> findByUserIdAndRoletype(Long userId, RoleType roleType);

    // ── Uniqueness Check ──────────────────────────────────────────────────────
    //
    // The unique index idx_role_user_role_unique ensures that one user cannot
    // have the same role twice at the DB level.
    // This check at the application level gives a clear business error message
    // BEFORE hitting the DB constraint (which would throw a cryptic ConstraintViolationException).
    //
    // Pattern: application-level check first → friendly error, then DB constraint as safety net.

    boolean existsByUserIdAndRoletype(Long userId, RoleType roleType);

    // ── Admin Queries ─────────────────────────────────────────────────────────
    //
    // Use case: admin panel "show all users with SALES role".
    // This query traverses Role → User to return User objects.
    //
    // WHY return List<Role> and not List<User>?
    // We need the Role context (when was it assigned, etc.) — not just the User.
    // The admin panel can access role.getUser() to get user details.
    // If we returned List<User>, we'd lose the Role information.
    //
    // EntityGraph loads User with each Role to avoid N+1 when rendering the list.

    @Query("SELECT r FROM Role r WHERE r.roletype = :roleType")
    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {"user"})
    List<Role> findAllByRoletypeWithUser(@Param("roleType") RoleType roleType);

    // ── Bulk Delete ───────────────────────────────────────────────────────────
    //
    // Use case: revoke all roles from a user (when deactivating the account).
    //
    // WHY @Modifying DELETE instead of user.getRoles().clear() + save?
    //
    // user.getRoles().clear() + save approach:
    //   1. Hibernate loads all roles (SELECT)
    //   2. For each role: DELETE role WHERE id = ?  (N DELETE queries)
    //   TOTAL: 1 SELECT + N DELETEs
    //
    // @Modifying DELETE approach:
    //   DELETE FROM roles WHERE user_id = :userId
    //   TOTAL: 1 DELETE — database handles "N" internally
    //
    // For an admin user with 3 roles: the difference is small.
    // For a system with thousands of users and a migration script: huge difference.
    //
    // Note: since User.roles has orphanRemoval = true, we could also use
    // user.getRoles().clear() + userRepository.save(user). The @Modifying
    // approach is provided here to demonstrate the pattern and for batch operations.

    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM Role r WHERE r.user.id = :userId")
    void deleteAllByUserId(@Param("userId") Long userId);
}