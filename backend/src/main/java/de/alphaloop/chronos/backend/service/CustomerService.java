package de.alphaloop.chronos.backend.service;

import de.alphaloop.chronos.backend.domain.Customer;
import de.alphaloop.chronos.backend.exception.ConflictException;
import de.alphaloop.chronos.backend.exception.ResourceNotFoundException;
import de.alphaloop.chronos.backend.repository.CustomerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CustomerService — business logic for Customer management (Kundenverwaltung).
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * ARCHITECTURAL DECISIONS:
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * WHY @Service?
 * Marks this as a Spring-managed bean in the Service layer.
 * Spring's component scanning detects it and registers it in the ApplicationContext.
 * The @Service stereotype (vs @Component) communicates intent to other developers:
 * "this class contains business logic, not a repository, not a controller".
 *
 * WHY @RequiredArgsConstructor (Lombok)?
 * Generates a constructor with all final fields as parameters.
 * Spring injects dependencies via constructor injection (best practice):
 *   - Dependencies are immutable (final fields) — no accidental reassignment
 *   - Testable without Spring context: new CustomerService(mockRepo)
 *   - No @Autowired field injection (which hides dependencies and breaks tests)
 *
 * WHY @Slf4j (Lombok)?
 * Generates: private static final Logger log = LoggerFactory.getLogger(CustomerService.class);
 * In production, logs help diagnose issues without a debugger.
 * A rule of thumb: log at INFO for business events, DEBUG for technical details.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * @TRANSACTIONAL STRATEGY:
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * Class-level @Transactional(readOnly = true):
 *   Applied to ALL methods by default.
 *   readOnly=true tells Hibernate: "no entities will be modified in this transaction".
 *   Hibernate optimizations for readOnly:
 *     1. Skips dirty checking — no need to compare every field before flush
 *     2. Does not register entities in the first-level cache write-back
 *     3. PostgreSQL can route to read replicas (if configured)
 *
 * Method-level @Transactional (without readOnly):
 *   Overrides the class-level annotation for write operations.
 *   Only the methods that actually modify data need full read-write transactions.
 *
 * This pattern: @Transactional(readOnly=true) on class + @Transactional on write methods
 * is the standard Spring service pattern for ERP applications.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CustomerService {

    private final CustomerRepository customerRepository;

    // ── READ OPERATIONS ───────────────────────────────────────────────────────

    /**
     * Returns a paginated list of active customers.
     *
     * WHY Pageable as a parameter?
     * The service does not decide page size — the caller (controller) does.
     * This makes the service reusable: the web controller passes page 20 items,
     * a batch job could pass page 1000 items — same method, different Pageable.
     *
     * The transaction is readOnly=true (inherited from class level).
     * No entities are modified → Hibernate skips dirty checking → faster.
     */
    public Page<Customer> getActiveCustomers(Pageable pageable) {
        log.debug("Fetching active customers, page: {}", pageable.getPageNumber());
        return customerRepository.findByActiveTrue(pageable);
    }

    /**
     * Search customers by name with optional active-only filter.
     *
     * WHY pass null for name when no search term?
     * The JPQL query uses: :name IS NULL OR LOWER(c.name) LIKE ...
     * When name=null → the IS NULL check short-circuits → all names match.
     * One query handles both "search" and "list all" cases.
     */
    public Page<Customer> searchCustomers(String name, boolean activeOnly, Pageable pageable) {
        log.debug("Searching customers: name='{}', activeOnly={}", name, activeOnly);
        // Pass null to trigger "match all" branch in JPQL query
        String searchTerm = (name != null && !name.isBlank()) ? name.trim() : null;
        return customerRepository.searchCustomers(searchTerm, activeOnly, pageable);
    }

    /**
     * Load customer with their projects in a single query (avoids N+1).
     * Used for the customer detail page.
     *
     * WHY "WithProjects" variant?
     * Simple getById() loads Customer without projects (LAZY).
     * For the detail page, we need projects → use EntityGraph variant.
     * Using the wrong method here would trigger N+1 when rendering project list.
     */
    public Customer getByIdWithProjects(Long id) {
        return customerRepository.findByIdWithProjects(id)
                .orElseThrow(() -> ResourceNotFoundException.customer(id));
    }

    /**
     * Basic lookup without collections — for internal service use.
     * E.g.: ProjectService needs the Customer object to link a new project.
     * No need to load all projects just for that.
     */
    public Customer getById(Long id) {
        return customerRepository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.customer(id));
    }

    // ── WRITE OPERATIONS ──────────────────────────────────────────────────────

    /**
     * Create a new customer.
     *
     * @Transactional without readOnly=true — overrides the class-level annotation.
     * This starts a full read-write transaction:
     *   1. Spring opens a DB transaction
     *   2. existsByEmail check runs inside the transaction
     *   3. save() runs inside the same transaction
     *   4. Both operations are ATOMIC — either both succeed or both rollback
     *
     * WHY check email uniqueness here AND in the DB (unique constraint)?
     * Application-level check: gives a friendly, human-readable error message.
     * DB constraint: last line of defense — catches concurrent inserts
     * (two requests check "email free" simultaneously, both get true,
     * then both try to insert → the second one hits the DB constraint).
     *
     * Two-layer validation is the industry standard for uniqueness.
     */
    @Transactional
    public Customer create(Customer customer) {
        log.info("Creating customer: email={}", customer.getEmail());

        if (customerRepository.existsByEmail(customer.getEmail())) {
            throw ConflictException.emailAlreadyExists(customer.getEmail());
        }

        // active defaults to true — new customers are active
        customer.setActive(true);
        Customer saved = customerRepository.save(customer);

        log.info("Customer created: id={}, email={}", saved.getId(), saved.getEmail());
        return saved;
    }

    /**
     * Update customer data.
     *
     * WHY load the entity first instead of using a bulk UPDATE query?
     *
     * Bulk UPDATE approach: @Modifying @Query("UPDATE Customer c SET c.name=:name WHERE c.id=:id")
     *   - Fast: no SELECT, one UPDATE
     *   - Problem: Hibernate's first-level cache has stale data after the bulk UPDATE
     *   - Problem: @Version (if added later) would not be respected
     *   - Problem: JPA lifecycle callbacks (@PreUpdate) are NOT called
     *
     * Load-and-save approach (our approach):
     *   - SELECT + UPDATE = 2 queries, but:
     *   - @PreUpdate lifecycle hooks fire (audit fields, business rules)
     *   - @Version is respected (optimistic locking)
     *   - First-level cache is consistent
     *   - Standard JPA pattern — predictable behavior
     *
     * For a single entity update, 2 queries vs 1 is negligible.
     * Bulk UPDATE is only justified for batch operations (hundreds of rows).
     *
     * EMAIL CHANGE: if the new email differs, check uniqueness again.
     * If email unchanged: skip the check (would fail with "already exists" for itself).
     */
    @Transactional
    public Customer update(Long id, Customer updatedData) {
        log.info("Updating customer: id={}", id);

        Customer existing = getById(id);

        boolean emailChanged = !existing.getEmail().equalsIgnoreCase(updatedData.getEmail());
        if (emailChanged && customerRepository.existsByEmail(updatedData.getEmail())) {
            throw ConflictException.emailAlreadyExists(updatedData.getEmail());
        }

        // Update only the fields that are allowed to change.
        // We do NOT call existing = updatedData — that would replace the managed entity
        // with a detached object, breaking Hibernate's dirty checking.
        existing.setName(updatedData.getName());
        existing.setEmail(updatedData.getEmail());
        existing.setPhone(updatedData.getPhone());

        // No explicit save() needed here!
        // WHY? The entity is "managed" (loaded within this transaction).
        // Hibernate's dirty checking detects the field changes automatically.
        // At transaction commit, Hibernate generates UPDATE SQL.
        // This is called "automatic dirty checking" — a core Hibernate feature.
        log.info("Customer updated: id={}", id);
        return existing;
    }

    /**
     * Soft-delete: deactivates a customer instead of removing the row.
     *
     * WHY soft-delete and not hard DELETE?
     *
     * Hard delete would cascade: Customer → Projects → Orders → OrderItems → Availability.
     * That would destroy YEARS of financial history for an ERP company like Lang GmbH.
     * Violates legal requirements: German law (GoBD) requires financial records
     * to be preserved for 10 years.
     *
     * Soft-delete: set active=false.
     *   - All historical data preserved
     *   - Customer appears nowhere in active lists
     *   - Can be reactivated if needed
     *   - Audit trail complete
     *
     * This is standard practice in ERP systems. "Delete" in ERP means "archive".
     */
    @Transactional
    public void deactivate(Long id) {
        log.info("Deactivating customer: id={}", id);
        Customer customer = getById(id);
        customer.setActive(false);
        // Again: no explicit save() — dirty checking handles it.
    }
}