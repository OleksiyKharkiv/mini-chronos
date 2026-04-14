package de.alphaloop.chronos.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * SecurityConfig — early security configuration.
 * <p>
 * Provides PasswordEncoder bean required by UserService for password hashing.
 * Full Spring Security (JWT, endpoint protection) is currently disabled in pom.xml
 * to allow focusing on domain logic during initial development.
 */
@Configuration
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
