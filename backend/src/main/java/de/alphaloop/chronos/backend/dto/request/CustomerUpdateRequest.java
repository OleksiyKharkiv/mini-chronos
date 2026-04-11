package de.alphaloop.chronos.backend.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request DTO for updating an existing customer.
 *
 * Identical fields to create — but a SEPARATE record.
 * WHY not reuse CustomerCreateRequest?
 *
 * In the future, update might allow partial updates (PATCH semantics)
 * where some fields are optional. Or update might disallow email changes.
 * Having separate records means you change one without affecting the other.
 *
 * Also, semantics differ: "create" implies all required fields present,
 * "update" might mean "only send what changed". Separate types, separate intent.
 */
public record CustomerUpdateRequest(

        @NotBlank(message = "Customer name is required")
        @Size(max = 255)
        String name,

        @NotBlank
        @Email(message = "Email must be a valid email address")
        @Size(max = 255)
        String email,

        @Size(max = 50)
        String phone

) {}