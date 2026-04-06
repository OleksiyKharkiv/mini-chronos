package de.alphaloop.chronos.backend.domain;
// backend/src/main/java/de/alphaloop/demo/chronos/domain/BaseEntity.java

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Abstract base class for all entities.
 * <p>
 * Design decisions:
 * 1. @MappedSuperclass - not an entity itself, provides common structure
 * 2. @EntityListeners(AuditingEntityListener.class) - automatic audit fields
 * 3. GenerationType.SEQUENCE - best for PostgreSQL, avoids ID exhaustion
 * 4. equals/hashCode based on ID - required for Hibernate proxy correctness
 * <p>
 * References:
 * - Baeldung: <a href="https://www.baeldung.com/hibernate-inheritance">Hibernate Inheritance</a>
 * - Hibernate Docs: <a href="https://docs.jboss.org/hibernate/orm/6.4/userguide/html_single/Hibernate_User_Guide.html#entity-inheritance">Hibernate Entity Inheritance</a>
 */

@Getter
@Setter
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "base_seq")
    @SequenceGenerator(name = "base_seq", sequenceName = "base_sequence")
    private Long id;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    // Constructors
    protected BaseEntity() {
        // Required by JPA
    }

    /**
     * CRITICAL: equals/hashCode based on ID for Hibernate proxies.
     * <p>
     * Why not use all fields?
     * - Hibernate proxies (lazy loading) have only ID populated initially
     * - Comparing proxy with real entity would fail if based on other fields
     * <p>
     * Why check Class instead of instanceof?
     * - Subclasses must have their own identity
     * - Prevents false equality between Customer and User (both extend BaseEntity)
     * <p>
     * Reference: <a href="https://docs.jboss.org/hibernate/orm/6.4/userguide/html_single/Hibernate_User_Guide.html#mapping-model-pojo-equalshashcode">Hibernate equals/hashCode</a>
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BaseEntity that = (BaseEntity) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "{id=" + id + "}";
    }
}