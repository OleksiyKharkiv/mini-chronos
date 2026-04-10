package de.alphaloop.chronos.backend.repository;

import de.alphaloop.chronos.backend.domain.Customer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Repository for Customer entity.
 * <p>
 * WHY JpaRepository<Customer, Long>?
 * JpaRepository extends CrudRepository and PagingAndSortingRepository.
 * This gives us for free:
 *   - save(), findById(), findAll(), deleteById() — basic CRUD
 *   - findAll(Pageable) — pagination (critical for ERP lists with 10k+ customers)
 *   - flush(), saveAndFlush() — explicit flush control
 *   - count(), existsById() — aggregate operations
 * <p>
 * WHY @Repository?
 * Technically optional when extending JpaRepository (Spring detects it),
 * but explicit @Repository has one important benefit: it enables Spring's
 * PersistenceExceptionTranslationPostProcessor to translate JPA-specific
 * exceptions (like HibernateException) into Spring's DataAccessException hierarchy.
 * This means your service layer catches Spring exceptions, not Hibernate-specific ones,
 * keeping the service layer decoupled from the persistence provider.
 */
@Repository
public interface CustomerRepository extends JpaRepository<Customer, Long> {

    // ── Derived Query Methods ─────────────────────────────────────────────────
    //
    // Spring Data JPA parses the method name and generates JPQL automatically.
    // Convention: find + By + FieldName + Condition
    //
    // Generated SQL for findByEmail:
    //   SELECT c.* FROM customers c WHERE c.email = ?
    //   Uses idx_customer_email index → O(log n) lookup
    //
    // WHY Optional<> return type?
    // Forces the caller to handle the "not found" case explicitly.
    // Without Optional: the caller gets null and forgets null-check → NullPointerException.
    // With Optional: caller MUST call .orElseThrow() or .ifPresent() — safer API.

    Optional<Customer> findByEmail(String email);

    // ── Pagination + Filtering ────────────────────────────────────────────────
    //
    // WHY Page<Customer> instead of List<Customer>?
    // A list loads ALL matching rows into memory.
    // Page loads only one page (e.g., 20 rows) + runs COUNT(*) for total pages.
    // With 50.000 customers at Lang GmbH, List would load 50k objects on every request.
    // Page sends only 20 objects — 2500x less memory and data transfer.
    //
    // Pageable pageable → caller passes: PageRequest.of(0, 20, Sort.by("name"))
    // This makes the repository reusable: controller decides page size and sorting.
    //
    // WHY findByActiveTrue instead of findByActive(boolean)?
    // In ERP, inactive customers are archived but never deleted (legal/audit requirement).
    // The most common query is always "give me active customers".
    // A dedicated method is clearer and slightly faster (no parameter binding overhead).

    Page<Customer> findByActiveTrue(Pageable pageable);

    // ── Full-text Search ──────────────────────────────────────────────────────
    //
    // Derived methods only support exact match or simple conditions.
    // For ILIKE (case-insensitive search) we need @Query.
    //
    // WHY JPQL instead of native SQL?
    //   - JPQL is database-agnostic (works on PostgreSQL, H2, MySQL)
    //   - H2 in tests, PostgreSQL in production → the same JPQL works in both
    //   - Native SQL would use PostgreSQL-specific ILIKE, breaking H2 tests
    //
    // WHY LOWER() instead of ILIKE?
    //   - ILIKE is a PostgreSQL-specific syntax, not in JPQL standard
    //   - LOWER(:name) + LOWER(c.name) → case-insensitive comparison in pure JPQL
    //
    // The CONCAT('%', :name, '%') is JPQL-safe string concatenation.
    // Equivalent SQL: WHERE LOWER(name) LIKE LOWER('%searchTerm%')
    //
    // countQuery: Spring Data needs a separate COUNT query for pagination.
    // Without it, Spring tries to wrap the full query in COUNT(*),
    // which can fail with complex queries. Explicit countQuery is the best practice.

    @Query(
            value = """
                    SELECT c FROM Customer c
                    WHERE (:name IS NULL OR LOWER(c.name) LIKE LOWER(CONCAT('%', :name, '%')))
                    AND (:activeOnly = false OR c.active = true)
                    ORDER BY c.name ASC
                    """,
            countQuery = """
                    SELECT COUNT(c) FROM Customer c
                    WHERE (:name IS NULL OR LOWER(c.name) LIKE LOWER(CONCAT('%', :name, '%')))
                    AND (:activeOnly = false OR c.active = true)
                    """
    )
    Page<Customer> searchCustomers(
            @Param("name") String name,
            @Param("activeOnly") boolean activeOnly,
            Pageable pageable
    );

    // ── EntityGraph: solving N+1 for Customer + Projects ─────────────────────
    //
    // THE N+1 PROBLEM EXPLAINED:
    //
    // Scenario: load 20 customers, then access customer.getProjects() for each.
    //
    // Without EntityGraph (LAZY loading):
    //   Query 1:  SELECT * FROM customers LIMIT 20          → 20 rows
    //   Query 2:  SELECT * FROM projects WHERE customer_id=1 → for customer #1
    //   Query 3:  SELECT * FROM projects WHERE customer_id=2 → for customer #2
    //   ...
    //   Query 21: SELECT * FROM projects WHERE customer_id=20
    //   TOTAL: 1 + 20 = 21 queries! (N+1 where N=20)
    //
    // With EntityGraph (JOIN FETCH):
    //   SELECT c.*, p.* FROM customers c
    //   LEFT JOIN projects p ON p.customer_id = c.id
    //   LIMIT 20
    //   TOTAL: 1 query! ← this is what we want
    //
    // WHY @EntityGraph instead of JOIN FETCH in @Query?
    // @EntityGraph is reusable across multiple query methods without duplicating
    // the JOIN FETCH clause. Also, Spring Data handles the pagination correctly
    // (JOIN FETCH + Pageable can cause HHH90003004 warning — EntityGraph avoids this).
    //
    // attributePaths: names of the @OneToMany/@ManyToOne fields to eagerly load.
    // "projects" → refers to the field name in the Customer entity, not the table name.

    @Query("SELECT c FROM Customer c WHERE c.id = :id")
    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {"projects"})
    Optional<Customer> findByIdWithProjects(@Param("id") Long id);

    // ── Existence check ───────────────────────────────────────────────────────
    //
    // WHY existsByEmail instead of findByEmail().isPresent()?
    // existsBy generates: SELECT 1 FROM customers WHERE email = ? LIMIT 1
    // findBy generates:   SELECT * FROM customers WHERE email = ?
    // For a uniqueness check before saving, we don't need the full entity — just a boolean.
    // existsBy is more efficient: no object materialization, minimal data transfer.

    boolean existsByEmail(String email);
}