package de.alphaloop.chronos.backend.dto.request;


import de.alphaloop.chronos.backend.enums.RoleType;
import jakarta.validation.constraints.*;

/**
 * Request for creating a new system user.
 * <p>
 * SECURITY NOTE on password:
 * The plain text password arrives here over HTTPS (encrypted in transit).
 * UserService immediately hashes it with BCrypt and discards the plaintext.
 * The password field is NEVER logged, NEVER stored, NEVER returned in a response.
 *
 * @Size(min = 8): enforce the minimum password length at the API level.
 * In production, add @Pattern for complexity rules (uppercase, digit, special char).
 */
public record UserCreateRequest(

        @NotBlank(message = "Username is required")
        @Size(min = 3, max = 100, message = "Username must be 3-100 characters")
        @Pattern(regexp = "^[a-zA-Z0-9._-]+$",
                message = "Username may only contain letters, digits, dots, underscores, hyphens")
        String username,

        @NotBlank
        @Email(message = "Email must be a valid email address")
        @Size(max = 255)
        String email,

        @NotBlank(message = "Password is required")
        @Size(min = 8, message = "Password must be at least 8 characters")
        String password,  // plaintext — hashed immediately in UserService

        @NotBlank(message = "First name is required")
        @Size(max = 100)
        String firstName,

        @NotBlank(message = "Last name is required")
        @Size(max = 100)
        String lastName,

        @NotNull(message = "Initial role is required")
        RoleType initialRole

) {
}
