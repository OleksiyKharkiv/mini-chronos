package de.alphaloop.chronos.backend.dto.response;

import java.time.LocalDateTime;

// ═══════════════════════════════════════════════════════════════════════════
// WHY MULTIPLE RESPONSE DTOs PER ENTITY (e.g., CustomerResponse + CustomerDetailResponse)?
//
// Different API endpoints need different amounts of data:
//
// GET /api/customers → list view: id, name, email, active
//   Loading 50 customers with all their projects would be:
//   50 customers × N projects each → potentially thousands of objects for a list page.
//   CustomerResponse: LEAN — only what the list table needs.
//
// GET /api/customers/{id} → detail view: all fields + projects
//   One customer, full data including a project list.
//   CustomerDetailResponse: RICH — everything for the detail page.
//
// This is called "DTO projection" — each view gets exactly the data it needs.
// No over-fetching (sending too much), no under-fetching (needing a second request).
//
// WHY RECORDS FOR RESPONSE DTOs TOO?
// Response DTOs travel one way: Service → Controller → Jackson → JSON.
// They are created once, read once, serialized. Immutability is perfect here.
// Jackson serializes records identically to classes with getters.
// ═══════════════════════════════════════════════════════════════════════════


// ─── CUSTOMER ────────────────────────────────────────────────────────────────

/**
 * Lean customer response for list views and cross-references.
 * Used in: GET /api/customers (list), embedded in ProjectResponse, etc.
 * <p>
 * Contains NO projects list — that would cause N+1 for every list item.
 */
public record CustomerResponse(
        Long id,
        String name,
        String email,
        String phone,
        boolean active,
        LocalDateTime createdAt
) {}