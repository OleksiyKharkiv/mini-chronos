package de.alphaloop.chronos.backend.domain;

import de.alphaloop.chronos.backend.enums.ProjectStatus;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "projects")
public class Project extends BaseEntity {

    @NotBlank
    @Size(max = 255)
    @Column(nullable = false)
    private String name;

    /**
     * @ManyToOne - owning side
     * - This table has a customer_id column
     * - FetchType.LAZY - CRITICAL for performance
     * (default was EAGER in JPA 1.0, LAZY since 2.0, but explicit is better)
     * - optional = false - inner join in queries, not left join
     */
    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ProjectStatus status = ProjectStatus.DRAFT;

    private LocalDate startDate;
    private LocalDate endDate;

    @Size(max = 2000)
    @Column(length = 2000)
    private String description;


    /**
     * orphanRemoval = true:
     * - If project is removed from customer's list, delete from DB
     * - But we use REMOVE cascade carefully - only if business allows
     *
     * Here: NO REMOVE cascade - orders have financial history
     */
    @OneToMany(
            mappedBy = "project",
            fetch = FetchType.LAZY,
            cascade = {CascadeType.PERSIST, CascadeType.MERGE}
    )
    private List<Order> orders = new ArrayList<>();

    // Constructors, getters, setters, helpers...

    public void addOrder(Order order) {
        orders.add(order);
        order.setProject(this);
    }
}