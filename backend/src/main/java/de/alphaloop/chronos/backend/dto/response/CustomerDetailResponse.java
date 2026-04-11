package de.alphaloop.chronos.backend.dto.response;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Rich customer response for the detail page.
 * Includes the projects list — loaded via EntityGraph in CustomerService.
 * <p>
 * WHY List<ProjectResponse> and not List<Project>?
 * Returning List<Project> would serialize the entire entity graph:
 * projects → orders → orderItems → equipment → availabilityRecords → ...
 * This is the "Jackson infinite recursion" problem with bidirectional JPA relations.
 * ProjectResponse is a controlled, finite snapshot — no cycles possible.
 */
public record CustomerDetailResponse(
        Long id,
        String name,
        String email,
        String phone,
        boolean active,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        List<ProjectResponse> projects  // pre-mapped, no lazy loading risk
) {}