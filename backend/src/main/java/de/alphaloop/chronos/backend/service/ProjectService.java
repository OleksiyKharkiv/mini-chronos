package de.alphaloop.chronos.backend.service;

import de.alphaloop.chronos.backend.domain.Customer;
import de.alphaloop.chronos.backend.domain.Project;
import de.alphaloop.chronos.backend.exception.BusinessRuleException;
import de.alphaloop.chronos.backend.exception.ResourceNotFoundException;
import de.alphaloop.chronos.backend.enums.ProjectStatus;
import de.alphaloop.chronos.backend.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * ProjectService — business logic for Project management (Projektverwaltung).
 *
 * Project sits at the center of the hierarchy: Customer → Project → Order.
 * Key responsibilities:
 *   - Create projects linked to customers
 *   - Manage project lifecycle (status transitions)
 *   - Provide project views with different levels of eager loading
 *
 * STATUS TRANSITION RULES (enforced by ProjectStatus.canTransitionTo()):
 *   DRAFT      → ACTIVE only
 *   ACTIVE     → any status
 *   ON_HOLD    → ACTIVE or CANCELLED
 *   COMPLETED  → (terminal, no transitions)
 *   CANCELLED  → (terminal, no transitions)
 *
 * The transition logic lives in the ENUM (ProjectStatus.canTransitionTo()),
 * not in the service. This is the "Rich Domain Model" approach:
 * business rules that belong to the entity are expressed in the entity/enum,
 * not scattered across service methods.
 * The service only coordinates: load, delegate to domain, save.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final CustomerService customerService;

    // ── READ OPERATIONS ───────────────────────────────────────────────────────

    /**
     * Project list for a customer (e.g. customer detail page sidebar).
     * Uses EntityGraph → loads customer in the same query (for breadcrumb).
     */
    public Page<Project> getByCustomer(Long customerId, Pageable pageable) {
        // Verify the customer exists first — fail fast with a clear error.
        // Without this check: projectRepository.findByCustomerId() on a non-existent
        // customer silently returns an empty page — no indication of the real problem.
        customerService.getById(customerId); // throws ResourceNotFoundException if not found

        return projectRepository.findByCustomerId(customerId, pageable);
    }

    /**
     * Project detail page — needs customer name (breadcrumb) + order list.
     * EntityGraph loads all three in one query.
     */
    public Project getByIdWithDetails(Long id) {
        return projectRepository.findByIdWithCustomerAndOrders(id)
                .orElseThrow(() -> ResourceNotFoundException.project(id));
    }

    /**
     * Internal lookup — for services that just need the Project entity.
     * No collection loading — caller decides if they need orders/customer.
     *
     * NOTE: returns Project with customer loaded (overridden findById uses EntityGraph).
     * See ProjectRepository.findById() — it always loads customer.
     */
    public Project getById(Long id) {
        return projectRepository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.project(id));
    }

    /**
     * Dashboard: active projects with customer names.
     * Uses JOIN FETCH in repository — no N+1 on customer.getName().
     */
    public List<Project> getActiveProjectsForDashboard() {
        return projectRepository.findActiveWithCustomer(ProjectStatus.ACTIVE);
    }

    // ── WRITE OPERATIONS ──────────────────────────────────────────────────────

    /**
     * Create a new project for a customer.
     *
     * WHY load the Customer entity here instead of using just customerId?
     *
     * Option A — set only the ID:
     *   project.setCustomer(entityManager.getReference(Customer.class, customerId));
     *   This creates a proxy with only the ID — Hibernate inserts without a SELECT.
     *   Faster, but skips validation: what if customerId doesn't exist?
     *   The INSERT would fail with a FK constraint error — confusing message.
     *
     * Option B — load the full Customer (our approach):
     *   Customer customer = customerService.getById(customerId); // SELECT
     *   project.setCustomer(customer);
     *   One extra SELECT, but:
     *     - Clear ResourceNotFoundException if customer not found
     *     - Can validate business rules: is the customer active?
     *     - Customer object available for any further checks
     *
     * For a user-facing API, clear error messages > micro-optimization.
     */
    @Transactional
    public Project create(Long customerId, Project project) {
        log.info("Creating project for customer: customerId={}, name={}", customerId, project.getName());

        Customer customer = customerService.getById(customerId);

        // Business rule: cannot create projects for deactivated customers.
        if (!customer.isActive()) {
            throw new BusinessRuleException(
                    "Cannot create project for deactivated customer: " + customer.getName()
            );
        }

        project.setCustomer(customer);
        project.setStatus(ProjectStatus.DRAFT); // always start in DRAFT

        Project saved = projectRepository.save(project);
        log.info("Project created: id={}, customerId={}", saved.getId(), customerId);
        return saved;
    }

    /**
     * Update project data (name, dates, description).
     * Status is NOT updated here — use transitionStatus() for that.
     *
     * WHY separate methods for data update and status transition?
     * Status transition has business rules (canTransitionTo).
     * Data update does not. Mixing them in one method means the caller always
     * has to handle status validation even when only changing the name.
     * Separate methods = separate concerns = cleaner API.
     */
    @Transactional
    public Project update(Long id, Project updatedData) {
        log.info("Updating project: id={}", id);

        Project existing = getById(id);

        // Prevent updates to completed/cancelled projects.
        // Closed projects are historical records — immutable.
        if (existing.getStatus() == ProjectStatus.COMPLETED
                || existing.getStatus() == ProjectStatus.CANCELLED) {
            throw new BusinessRuleException(
                    "Cannot update a project in terminal status: " + existing.getStatus()
            );
        }

        existing.setName(updatedData.getName());
        existing.setStartDate(updatedData.getStartDate());
        existing.setEndDate(updatedData.getEndDate());
        existing.setDescription(updatedData.getDescription());

        // Dirty checking — no explicit save() needed.
        return existing;
    }

    /**
     * Transition project to a new status.
     *
     * This is a DOMAIN OPERATION, not a CRUD operation.
     * The difference:
     *   CRUD: "set status field to X" — no validation, direct assignment
     *   Domain: "request a business state change" — validates rules, may trigger side effects
     *
     * The canTransitionTo() logic lives in ProjectStatus enum.
     * WHY in the enum and not here?
     * Because "which transitions are valid for a given status" is DOMAIN KNOWLEDGE.
     * It belongs to the domain model. If someone uses the enum without the service,
     * the rules still apply. The service just coordinates the operation.
     *
     * @param id            project to transition
     * @param targetStatus  desired new status
     * @return updated project
     * @throws BusinessRuleException if the transition is not allowed
     */
    @Transactional
    public Project transitionStatus(Long id, ProjectStatus targetStatus) {
        log.info("Transitioning project status: id={}, target={}", id, targetStatus);

        Project project = getById(id);
        ProjectStatus currentStatus = project.getStatus();

        // Delegate the transition rule to the enum — single source of truth.
        if (!currentStatus.canTransitionTo(targetStatus)) {
            throw new BusinessRuleException(
                    "Project status transition not allowed: " + currentStatus + " → " + targetStatus
            );
        }

        project.setStatus(targetStatus);
        log.info("Project status changed: id={}, {} → {}", id, currentStatus, targetStatus);
        return project;
    }
}