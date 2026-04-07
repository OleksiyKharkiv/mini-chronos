package de.alphaloop.chronos.backend.domain;

import de.alphaloop.chronos.backend.enums.OrderStatus;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;

/**
 * Order entity - central business entity with complex lifecycle.
 *
 * Key patterns:
 * 1. @Version - optimistic locking for concurrent modifications
 * 2. @OneToMany with orphanRemoval - items are part of order aggregate
 * 3. CascadeType.ALL for items - order controls item lifecycle
 * 4. @PrePersist/@PreUpdate - business logic hooks
 */
@Entity
@Table(name = "orders")
public class Order extends BaseEntity {
    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id")
    private Project project;

    private OrderStatus status = OrderStatus.DRAFT;

}
