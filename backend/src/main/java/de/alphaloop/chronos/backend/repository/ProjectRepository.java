package de.alphaloop.chronos.backend.repository;

import de.alphaloop.chronos.backend.domain.Project;
import de.alphaloop.chronos.backend.enums.ProjectStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for Project entity.
 *
 * Project sits in the middle of the hierarchy: Customer → Project → Order.
 * This means two common N+1 scenarios:
 *   1. Load project + its customer (ManyToOne — upward)
 *   2. Load project + its orders (OneToMany — downward)
 *
 * RULE OF THUMB for EntityGraph:
 *   - If you always need the related data → use EntityGraph
 *   - If you sometimes need it → use a separate findByIdWith... method
 *   - Never load both directions at once if you don't need both
 */
@Repository
public interface ProjectRepository extends JpaRepository<Project, Long> {

    // ── Basic Lookups ─────────────────────────────────────────────────────────

    // Derived method: Spring generates JOIN on customer_id.
    // WHY Page<Project>? A customer like Lang GmbH may have hundreds of projects.
    // Returning List would load ALL of them. Page returns 20 at a time.
    Page<Project> findByCustomerId(Long customerId, Pageable pageable);

    // Filter by status — common use case: show only ACTIVE projects in dashboard.
    Page<Project> findByStatus(ProjectStatus status, Pageable pageable);

    // Combined filter: active projects of a specific customer.
    // Derived method name: find + By + CustomerId + And + Status
    // Generated JPQL: WHERE p.customer.id = :customerId AND p.status = :status
    List<Project> findByCustomerIdAndStatus(Long customerId, ProjectStatus status);

    // ── EntityGraph: Project detail page (needs customer name) ───────────────
    //
    // Use case: ProjectController.getProjectDetail(id) — displays project card
    // with the customer name in the breadcrumb: "Lang GmbH > Jahreskonferenz 2026"
    //
    // Without EntityGraph:
    //   Query 1: SELECT * FROM projects WHERE id = 5
    //   Query 2: SELECT * FROM customers WHERE id = 482  ← triggered by project.getCustomer().getName()
    //   TOTAL: 2 queries for one simple detail page
    //
    // With EntityGraph ("customer"):
    //   SELECT p.*, c.* FROM projects p
    //   JOIN customers c ON c.id = p.customer_id
    //   WHERE p.id = 5
    //   TOTAL: 1 query
    //
    // WHY @EntityGraph on findById override instead of a new method?
    // The findById(id) is the most natural way to load one entity.
    // We override it here to ALWAYS load with customer for this repository.
    // If you need the project WITHOUT customer in some cases, create a separate method.

    @EntityGraph(attributePaths = {"customer"})
    Optional<Project> findById(Long id);

    // ── EntityGraph: Project + Orders (for project detail with order list) ────
    //
    // Use case: rendering the full project detail page with its order table.
    // We need project + customer (breadcrumb) + orders (order table).
    //
    // Multiple attributePaths = multiple JOINs in ONE query:
    //   SELECT p.*, c.*, o.*
    //   FROM projects p
    //   JOIN customers c ON c.id = p.customer_id
    //   LEFT JOIN orders o ON o.project_id = p.id
    //   WHERE p.id = :id

    @Query("SELECT p FROM Project p WHERE p.id = :id")
    @EntityGraph(attributePaths = {"customer", "orders"})
    Optional<Project> findByIdWithCustomerAndOrders(@Param("id") Long id);

    // ── Dashboard Query ───────────────────────────────────────────────────────
    //
    // Use case: Dashboard widget "Active projects" — shows name + customer name.
    //
    // WHY not findByStatus(ACTIVE)?
    // findByStatus returns Page<Project> with potential N+1 on customer.name.
    // This query pre-fetches customer in one JOIN, safe for dashboard rendering.
    //
    // WHY JPQL JOIN FETCH here instead of EntityGraph?
    // For List results (no pagination), JOIN FETCH is clean and explicit.
    // EntityGraph is preferred when you want to reuse across paginated queries.

    @Query("""
            SELECT p FROM Project p
            JOIN FETCH p.customer c
            WHERE p.status = :status
            ORDER BY p.createdAt DESC
            """)
    List<Project> findActiveWithCustomer(@Param("status") ProjectStatus status);

    // ── Statistics ────────────────────────────────────────────────────────────
    //
    // WHY return long instead of Long?
    // COUNT(*) never returns null from the DB — primitive long is safer here.
    // If you return Long (boxed), you'd need a null-check that is never triggered.

    long countByCustomerIdAndStatus(Long customerId, ProjectStatus status);
}