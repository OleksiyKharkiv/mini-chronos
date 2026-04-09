package de.alphaloop.chronos.backend.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(
        name = "users",
        indexes = {
                @Index(name = "idx_user_email", columnList = "email", unique = true),
                @Index(name = "idx_user_username", columnList = "username", unique = true),
                @Index(name = "idx_user_active", columnList = "active")
        }
)
public class User extends BaseEntity {
    @NotBlank
    @Size(max = 100)
    @Column(nullable = false, unique = true, length = 100)
    private String userName;

    @NotBlank
    @Email
    @Size(max = 255)
    @Column(nullable = false, unique = true)
    private String email;

    /**
     * ТОЛЬКО BCrypt hash — НИКОГДА plaintext!
     * Длина 255: BCrypt hash всегда 60 символов, запас есть.
     * updatable=true: пользователь может сменить пароль.
     */
    @NotBlank
    @Column(name = "password_hash", nullable = false)
    private String password_hash;

    // ----- Personal Data ----------------------------------
    @NotBlank
    @Column(name = "first_name", nullable = false, length = 100)
    private String firstName;
    @NotBlank
    @Column(name = "last_name", nullable = false, length = 100)
    private String lastName;

    // ----- Account Status -----------------------------------------------------
    @Column(nullable = false)
    private boolean active = true;

    // ── Roles ─────────────────────────────────────────────────────────────────

    /**
     * Роли пользователя через отдельную таблицу user_roles.
     * <p>
     * CascadeType.ALL: при создании User сразу сохраняются его Role-записи.
     * orphanRemoval=true: убрал роль из списка → запись в user_roles удалится.
     * fetch=LAZY: не загружать роли при каждой загрузке User
     * (но Spring Security требует роли при аутентификации — см. ниже).
     * <p>
     * ВАЖНО для Spring Security:
     * При аутентификации нужны роли. Если LAZY — будет LazyInitializationException
     * после закрытия транзакции. Решение: загружать User с ролями через
     *
     * @EntityGraph или JOIN FETCH в UserDetailsService.
     */
    @OneToMany(
            mappedBy = "user",
            cascade = CascadeType.ALL,
            orphanRemoval = true,
            fetch = FetchType.LAZY
    )
    private List<Role> roles = new ArrayList<>();

    // ---------- Helpers methods ----------──────────────────────────────────────────────────────────
    public void addRole(Role role) {
        roles.add(role);
        role.setUser(this);
    }

    public void removeRole(Role role) {
        roles.remove(role);
        role.setUser(null);
    }

    public String GEtDisplayName() {
        if (firstName != null && !firstName.isEmpty()) {
            return firstName + " " + lastName;
        }
        return userName;
    }
}