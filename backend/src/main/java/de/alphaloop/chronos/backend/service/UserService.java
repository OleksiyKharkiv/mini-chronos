package de.alphaloop.chronos.backend.service;

import de.alphaloop.chronos.backend.domain.Role;
import de.alphaloop.chronos.backend.domain.User;
import de.alphaloop.chronos.backend.enums.RoleType;
import de.alphaloop.chronos.backend.exception.BusinessRuleException;
import de.alphaloop.chronos.backend.exception.ConflictException;
import de.alphaloop.chronos.backend.exception.ResourceNotFoundException;
import de.alphaloop.chronos.backend.repository.RoleRepository;
import de.alphaloop.chronos.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * UserService — manages system users and their roles.
 *
 * Two responsibilities:
 *
 * 1. USER MANAGEMENT (admin use case):
 *    Create users, assign roles, deactivate accounts.
 *    These operations are performed by admins, not by the users themselves.
 *
 * 2. AUTHENTICATION SUPPORT:
 *    loadUserWithRoles() is called by Spring Security's UserDetailsService
 *    on every authenticated API request.
 *    Performance here directly impacts every API call latency.
 *
 * DEPENDENCY ON PasswordEncoder:
 * PasswordEncoder is a Spring Security bean (BCryptPasswordEncoder).
 * We inject it here to hash passwords before storing them.
 * The UserService should NEVER see plaintext passwords outside of this class.
 * BCrypt is a one-way hash — you cannot reverse it to get the original password.
 * Authentication works by hashing the input and comparing with the stored hash.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;

    // ── AUTHENTICATION SUPPORT ────────────────────────────────────────────────

    /**
     * Load user with roles for Spring Security authentication.
     *
     * This is the hottest method in the application — called on every request.
     * Uses EntityGraph to load user + roles in ONE query.
     * Without EntityGraph: LazyInitializationException when Spring Security
     * accesses user.getRoles() outside a transaction (in the JWT filter).
     *
     * WHY not just make roles FetchType.EAGER?
     * EAGER loads roles on EVERY User load — even when listing users in admin panel.
     * For a user list of 100 users, EAGER would load 100 × N role records.
     * The EntityGraph approach loads roles ONLY in this method (authentication).
     * Precise loading = better performance = lower DB load.
     */
    public User loadUserWithRoles(String username) {
        return userRepository.findByUsernameWithRoles(username)
                .orElseThrow(() -> new ResourceNotFoundException("User", username));
    }

    // ── READ OPERATIONS ───────────────────────────────────────────────────────

    public User getById(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.user(id));
    }

    // ── WRITE OPERATIONS ──────────────────────────────────────────────────────

    /**
     * Register a new user (admin operation).
     *
     * WHY hash the password in the service and not in the entity or controller?
     *
     * NOT in the entity: entities should not depend on Spring Security beans.
     *   An entity is a plain JPA object — injecting PasswordEncoder into it
     *   would create a Spring dependency in the domain layer. Wrong layer.
     *
     * NOT in the controller: the controller maps HTTP → domain. It should not
     *   contain security logic. If we add a second entry point (e.g. CLI admin tool),
     *   we'd have to duplicate the hashing logic.
     *
     * IN the service: correct. The service is responsible for enforcing
     *   all business rules before persisting, including: "passwords must be hashed".
     *
     * TRANSACTION:
     * Uniqueness checks + user save + role save → all atomic.
     * If role creation fails after user was saved → entire transaction rolls back.
     * No "user without role" state can exist after a partial failure.
     */
    @Transactional
    public User createUser(String username, String email,
                           String plainTextPassword, RoleType initialRole) {

        log.info("Creating user: username={}, email={}", username, email);

        // Two-layer uniqueness: application check (friendly message) + DB constraint (safety net).
        if (userRepository.existsByUserName(username)) {
            throw new ConflictException("Username already taken: " + username);
        }
        if (userRepository.existsByEmail(email)) {
            throw ConflictException.emailAlreadyExists(email);
        }

        User user = new User();
        user.setUserName(username);
        user.setEmail(email);
        user.setActive(true);

        // Hash the password BEFORE setting it on the entity.
        // After this line, the plaintext password should never be used or logged.
        // BCrypt adds a random salt automatically — same password produces different hashes.
        user.setPasswordHash(passwordEncoder.encode(plainTextPassword));

        // CascadeType.ALL on User.roles: save(user) would also save the role.
        // We use the factory method to keep the role creation logic in the domain class.
        User savedUser = userRepository.save(user);

        // Create initial role via factory method — keeps construction logic in Role.
        Role role = Role.of(savedUser, initialRole);
        roleRepository.save(role);

        // Manually add to the in-memory collection (the user object is already saved,
        // cascade won't run again). Keeps the returned object consistent with DB state.
        savedUser.addRole(role);

        log.info("User created: id={}, username={}, role={}", savedUser.getId(), username, initialRole);
        return savedUser;
    }

    /**
     * Assign an additional role to an existing user.
     *
     * WHY check existsByUserIdAndRoletype before adding?
     * The DB has a unique constraint (user_id, role_type).
     * Without the check: a duplicate insert would throw ConstraintViolationException —
     * a cryptic Hibernate error that leaks DB details to the API consumer.
     * With the check: a clear BusinessRuleException with a human-readable message.
     */
    @Transactional
    public Role assignRole(Long userId, RoleType roleType) {
        log.info("Assigning role {} to user {}", roleType, userId);

        User user = getById(userId);

        if (roleRepository.existsByUserIdAndRoletype(userId, roleType)) {
            throw new BusinessRuleException(
                    "User already has role: " + roleType
            );
        }

        Role role = Role.of(user, roleType);
        Role saved = roleRepository.save(role);
        user.addRole(role); // keep in-memory state consistent

        log.info("Role {} assigned to user {}", roleType, userId);
        return saved;
    }

    /**
     * Remove a specific role from a user.
     *
     * WHY not allow removing the last role?
     * A user with no roles cannot do anything in the system — effectively locked out.
     * This is usually not the intent. Use deactivate() to fully block access.
     *
     * WHY not just user.removeRole() + userRepository.save()?
     * That approach triggers orphanRemoval (the role is removed from the list)
     * which causes a DELETE via the JPA cascade.
     * The direct roleRepository.delete(role) approach:
     *   - Is explicit — reader immediately understands what happens
     *   - Works even if the user's roles collection is not loaded (LAZY)
     *   - Does not require loading all user roles just to remove one
     */
    @Transactional
    public void removeRole(Long userId, RoleType roleType) {
        log.info("Removing role {} from user {}", roleType, userId);

        // Verify the user actually has this role (clear error if not).
        Role role = roleRepository.findByUserIdAndRoletype(userId, roleType)
                .orElseThrow(() -> new BusinessRuleException(
                        "User does not have role: " + roleType
                ));

        // Prevent removing the last role — user must always have at least one.
        long remainingRoles = roleRepository.findByUserId(userId).size();
        if (remainingRoles <= 1) {
            throw new BusinessRuleException(
                    "Cannot remove the last role from a user. Deactivate the user instead."
            );
        }

        roleRepository.delete(role);
        log.info("Role {} removed from user {}", roleType, userId);
    }

    /**
     * Deactivate a user account — soft-delete, preserves all data.
     *
     * After deactivation:
     *   - Spring Security's UserDetailsService will load the user (loadUserWithRoles)
     *   - But authentication will fail because UserDetailsService checks user.isActive()
     *   - All historical data (orders, projects linked to this user) preserved
     *
     * WHY not delete the user?
     * Same reason as Customer deactivation: financial/audit history.
     * If Maria Müller created 500 orders over 3 years and then leaves the company,
     * deleting her account would orphan those orders or cascade-delete them.
     * Deactivation is always the safe choice in ERP.
     */
    @Transactional
    public void deactivate(Long userId) {
        log.info("Deactivating user: id={}", userId);
        User user = getById(userId);
        user.setActive(false);
        // Dirty checking → no explicit save() needed.
    }
}