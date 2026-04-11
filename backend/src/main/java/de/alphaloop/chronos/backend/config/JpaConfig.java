package de.alphaloop.chronos.backend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * JpaConfig — enables Spring Data JPA Auditing.
 * <p>
 * WHY THIS CLASS IS MANDATORY:
 * <p>
 * BaseEntity has two audit fields:
 *   @CreatedDate  private LocalDateTime createdAt;
 *   @LastModifiedDate private LocalDateTime updatedAt;
 * <p>
 * These annotations are processed by AuditingEntityListener,
 * which is registered via @EntityListeners(AuditingEntityListener.class)
 * on BaseEntity.
 * <p>
 * BUT: AuditingEntityListener only works if Spring Data Auditing is ENABLED.
 * Without @EnableJpaAuditing:
 *   - AuditingEntityListener is registered but does NOTHING
 *   - createdAt and updatedAt stay NULL on every entity
 *   - Liquibase's "created_at NOT NULL" constraint → INSERT fails
 *   - Application crashes on first save() call
 * <p>
 * This is one of the most common "works in theory, crashes in practice" bugs
 * that junior developers encounter. The fix is this single annotation.
 * <p>
 * WHY A SEPARATE CLASS and not on the main @SpringBootApplication class?
 * Single Responsibility Principle: the main class bootstraps the app,
 * this class owns JPA configuration. Easier to find, easier to test.
 * In larger projects, JpaConfig grows: custom converters, EntityManagerFactory,
 * transaction managers — they all live here.
 */
@Configuration
@EnableJpaAuditing
public class JpaConfig {
    // No beans needed — @EnableJpaAuditing does all the work.
    // Spring registers AuditingEntityListener globally for all entities
    // that use @EntityListeners(AuditingEntityListener.class).
}