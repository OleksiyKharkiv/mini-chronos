package de.alphaloop.chronos.backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record ProjectUpdateRequest(

        @NotBlank(message = "Project name is required")
        @Size(max = 255)
        String name,

        LocalDate startDate,
        LocalDate endDate,

        @Size(max = 2000)
        String description

) {
}
