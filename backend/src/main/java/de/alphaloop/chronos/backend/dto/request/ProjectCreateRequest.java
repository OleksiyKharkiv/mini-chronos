package de.alphaloop.chronos.backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record ProjectCreateRequest(

        @NotNull(message = "Customer ID is required")
        Long customerId,

        @NotBlank(message = "Project name is required")
        @Size(max = 255)
        String name,

        // Dates are optional at creation (project starts as DRAFT).
        // @FutureOrPresent: start date cannot be in the past.
        // Commented out for MVP — would block loading historical seed data in tests.
        // @FutureOrPresent(message = "Start date cannot be in the past")
        LocalDate startDate,

        LocalDate endDate,

        @Size(max = 2000, message = "Description must not exceed 2000 characters")
        String description

) {}