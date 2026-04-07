package de.alphaloop.chronos.backend.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "customers",
        indexes = {
                @jakarta.persistence.Index(name = "customer_name_idx", columnList = "name"),
                @jakarta.persistence.Index(name = "customer_email_idx", columnList = "email", unique = true),
                @jakarta.persistence.Index(name = "customer_active", columnList = "active")
        })
public class Customer extends BaseEntity {

    @NotBlank
    @Size(max = 255)
    @Column(nullable = false)
    private String name;

    @NotBlank
    @Size(max = 255)
    @Column(nullable = false, unique = true)
    private String email;
    @Size(max = 50)
    @Column(name = "phone_number")
    private String phone;

    @Column(nullable = false)
    private boolean active;

    @OneToMany(mappedBy = "customer",
            fetch = FetchType.LAZY,
            cascade = {CascadeType.PERSIST, CascadeType.MERGE})
    private List<Project> projects = new ArrayList<>();
}
