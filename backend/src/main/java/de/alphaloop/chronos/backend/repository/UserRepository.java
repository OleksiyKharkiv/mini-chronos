package de.alphaloop.chronos.backend.repository;

import de.alphaloop.chronos.backend.domain.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Repository for User entity.
 *
 * The most security-critical repository in the system.
 * UserDetailsService (Spring Security) calls this repository on every request
 * to authenticate the JWT token — performance here directly impacts API latency.
 *
 * KEY DESIGN DECISION: findByUsernameWithRoles
 * Spring Security needs the User's roles to build the Authentication object.
 * Roles are FetchType.LAZY on User. Without explicit loading, calling
 * user.getRoles() outside a transaction throws LazyInitializationException.
 *
 * Two solutions:
 *   1. Change roles to FetchType.EAGER → always loads roles on every User fetch
 *      → bad: loads roles even when you don't need them (user list, admin view)
 *   2. Dedicated method with EntityGraph → loads roles ONLY when needed for auth
 *      → correct: this is what we implement below
 */
@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    // ── Authentication — called by UserDetailsService ─────────────────────────
    //
    // This method is the HOTTEST path in the application.
    // Called on every authenticated API request (JWT filter validates token,
    // then calls loadUserByUsername to get the user + roles for the SecurityContext).
    //
    // EntityGraph loads roles in one JOIN instead of triggering a second query:
    //   SELECT u.*, r.*
    //   FROM users u
    //   LEFT JOIN roles r ON r.user_id = u.id
    //   WHERE u.username = ?
    //
    // Without EntityGraph, UserDetailsService would:
    //   Query 1: SELECT * FROM users WHERE username = ?
    //   Query 2: SELECT * FROM roles WHERE user_id = ?   ← LazyInitializationException
    //            (triggered outside the repo's transaction, in the filter)
    //
    // Why @Query + @EntityGraph instead of just @EntityGraph on findByUserName?
    // Spring Data's derived method parsing can conflict with @EntityGraph on
    // findByXxx methods with complex field names. Explicit @Query is more reliable
    // and more readable about what exactly is happening.

    @Query("SELECT u FROM User u WHERE u.userName = :username")
    @EntityGraph(attributePaths = {"roles"})
    Optional<User> findByUsernameWithRoles(@Param("username") String username);

    // ── Simple Lookup (without roles — for profile, admin list) ──────────────
    //
    // Use case: admin sees user list — we don't need roles for every row.
    // Use the simple derived method, no EntityGraph → no JOIN on roles.
    // Roles will be loaded lazily IF accessed within a transaction.
    // For user list rendering: just show name + email → roles not needed.

    Optional<User> findByEmail(String email);

    Optional<User> findByUserName(String userName);

    // ── Admin Panel Queries ───────────────────────────────────────────────────

    Page<User> findByActiveTrue(Pageable pageable);

    // ── Uniqueness Checks (used during registration / user creation) ──────────
    //
    // Service layer calls these BEFORE saving a new user:
    //   if (userRepo.existsByEmail(dto.email())) throw new ConflictException(...)
    //
    // existsBy generates SELECT 1 FROM users WHERE ... LIMIT 1
    // Much cheaper than findBy which materializes the full User object.

    boolean existsByEmail(String email);

    boolean existsByUserName(String userName);
}