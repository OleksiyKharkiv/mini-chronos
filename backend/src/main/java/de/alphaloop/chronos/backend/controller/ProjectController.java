package de.alphaloop.chronos.backend.controller;

import de.alphaloop.chronos.backend.domain.Project;
import de.alphaloop.chronos.backend.dto.request.ProjectCreateRequest;
import de.alphaloop.chronos.backend.dto.request.ProjectStatusRequest;
import de.alphaloop.chronos.backend.dto.request.ProjectUpdateRequest;
import de.alphaloop.chronos.backend.dto.response.ProjectDetailResponse;
import de.alphaloop.chronos.backend.dto.response.ProjectResponse;
import de.alphaloop.chronos.backend.mapper.ProjectMapper;
import de.alphaloop.chronos.backend.service.ProjectService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;

/**
 * ProjectController — REST API for Project (Vorgang) management.
 *
 * Demonstrates two REST design patterns worth discussing with Sebastian:
 *
 * 1. NESTED RESOURCE ROUTES:
 *    GET /api/customers/{customerId}/projects
 *    "Give me all projects for this customer."
 *    The parent resource (customer) is in the URL — makes the relationship explicit.
 *    Alternative: GET /api/projects?customerId=42 (flat route with query param).
 *    Both are valid REST. Nested routes are cleaner for "projects of a customer".
 *    Flat routes are better for "search projects across all customers".
 *    We use BOTH — each for its natural use case.
 *
 * 2. ACTION ENDPOINTS (status transition):
 *    PATCH /api/projects/{id}/status
 *    "Transition this project to a new status."
 *    This is NOT a full resource update (that's PUT).
 *    It's a SPECIFIC ACTION with business rules (canTransitionTo()).
 *    PATCH + a sub-path ("../status") is the REST-friendly way to express domain actions.
 *    Alternative: POST /api/projects/{id}/activate — also valid, more verb-like.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectService projectService;
    private final ProjectMapper  projectMapper;

    // ── GET /api/projects/active  ─────────────────────────────────────────────

    /**
     * Dashboard endpoint: all active projects with customer names.
     * Returns a flat list (not paginated) — dashboard shows all active projects.
     *
     * WHY List and not Page here?
     * The dashboard widget shows ALL active projects on one screen.
     * Pagination makes no UX sense for a widget with typically 10-50 items.
     * Page is for large, browsable lists. List is for bounded, complete results.
     */
    @GetMapping("/active")
    public ResponseEntity<List<ProjectResponse>> getActiveProjects() {
        List<Project> projects = projectService.getActiveProjectsForDashboard();
        return ResponseEntity.ok(projectMapper.toResponseList(projects));
    }

    // ── GET /api/customers/{customerId}/projects ──────────────────────────────

    /**
     * Projects for a specific customer — nested resource route.
     *
     * @PageableDefault(sort = "createdAt", direction = DESC):
     * Projects sorted newest-first. This is more useful than alphabetical
     * for a customer view — you want to see the most recent project first.
     *
     * Note: this method is on ProjectController but maps a /api/customers/... route.
     * WHY not put it in CustomerController?
     * The method uses ProjectService and ProjectMapper — it belongs in ProjectController.
     * Spring MVC doesn't require all /api/customers/* routes to be in CustomerController.
     * Routes are just URL patterns. Organize by the primary entity being returned.
     */
    @GetMapping("/by-customer/{customerId}")
    public ResponseEntity<Page<ProjectResponse>> getByCustomer(
            @PathVariable Long customerId,
            @PageableDefault(size = 20, sort = "createdAt") Pageable pageable
    ) {
        Page<Project> page = projectService.getByCustomer(customerId, pageable);
        return ResponseEntity.ok(page.map(projectMapper::toResponse));
    }

    // ── GET /api/projects/{id} ────────────────────────────────────────────────

    /**
     * Project detail page: project + customer + orders.
     * EntityGraph loads all three in one query (see ProjectRepository).
     */
    @GetMapping("/{id}")
    public ResponseEntity<ProjectDetailResponse> getById(@PathVariable Long id) {
        Project project = projectService.getByIdWithDetails(id);
        return ResponseEntity.ok(projectMapper.toDetailResponse(project));
    }

    // ── POST /api/projects ────────────────────────────────────────────────────

    /**
     * Create a new project.
     *
     * The customerId comes INSIDE the request body (ProjectCreateRequest.customerId).
     * Alternative design: POST /api/customers/{customerId}/projects (nested route).
     * We use the flat route here because the frontend sends a form with customerId
     * as a selected value — it's natural to include it in the body.
     *
     * The service handles: load customer, validate active, link, save.
     * The controller just maps and delegates — 4 lines of logic.
     */
    @PostMapping
    public ResponseEntity<ProjectResponse> create(
            @RequestBody @Valid ProjectCreateRequest request
    ) {
        Project project  = projectMapper.toEntity(request);
        Project saved    = projectService.create(request.customerId(), project);

        URI location = ServletUriComponentsBuilder
                .fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(saved.getId())
                .toUri();

        return ResponseEntity.created(location).body(projectMapper.toResponse(saved));
    }

    // ── PUT /api/projects/{id} ────────────────────────────────────────────────

    @PutMapping("/{id}")
    public ResponseEntity<ProjectResponse> update(
            @PathVariable Long id,
            @RequestBody @Valid ProjectUpdateRequest request
    ) {
        Project existing = projectService.getById(id);
        projectMapper.updateEntity(request, existing);
        Project updated  = projectService.update(id, existing);
        return ResponseEntity.ok(projectMapper.toResponse(updated));
    }

    // ── PATCH /api/projects/{id}/status ──────────────────────────────────────

    /**
     * Status transition — a domain action, not a data update.
     *
     * WHY PATCH and not PUT?
     * PUT replaces the entire resource. PATCH modifies part of it.
     * We're changing only the status field — PATCH is semantically correct.
     *
     * WHY a sub-path "/status" and not just PATCH /api/projects/{id}?
     * With PATCH /api/projects/{id}: the client sends any fields.
     * It's unclear whether status changes go through business rules or bypass them.
     * With PATCH /api/projects/{id}/status: the endpoint is EXPLICITLY for status changes.
     * The client knows to expect business rule validation (can't go COMPLETED → ACTIVE).
     * The controller routes directly to projectService.transitionStatus().
     *
     * HTTP 200 OK: PATCH that succeeds returns the updated resource.
     * (Unlike DELETE which returns 204 No Content.)
     */
    @PatchMapping("/{id}/status")
    public ResponseEntity<ProjectResponse> transitionStatus(
            @PathVariable Long id,
            @RequestBody @Valid ProjectStatusRequest request
    ) {
        Project updated = projectService.transitionStatus(id, request.status());
        return ResponseEntity.ok(projectMapper.toResponse(updated));
    }
}