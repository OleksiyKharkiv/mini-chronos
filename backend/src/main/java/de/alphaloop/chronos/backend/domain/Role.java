package de.alphaloop.chronos.backend.domain;

import de.alphaloop.chronos.backend.enums.RoleType;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Role — роль пользователя в системе (Benutzerrolle).
 * <p>
 * Каждая запись говорит: "Пользователь X имеет роль Y".
 * Одному User может соответствовать несколько Role-записей.
 * <p>
 * ─────────────────────────────────────────────────────────────────────
 * ПОЧЕМУ Role — это отдельная ENTITY, а не @ElementCollection?
 * ─────────────────────────────────────────────────────────────────────
 * <p>
 * Вариант 1 — @ElementCollection (проще):
 *
 * @ElementCollection(fetch = FetchType.EAGER)
 * @Enumerated(EnumType.STRING) private Set<RoleType> roles;
 * → Hibernate создаст таблицу user_roles(user_id, role)
 * → Нельзя добавить доп. поля (дата назначения, кем назначено)
 * → Нельзя сделать JOIN в сложных запросах
 * <p>
 * Вариант 2 — отдельная Entity (текущий подход, лучше для ERP):
 * → Можно добавить: assignedAt, assignedBy, expiresAt
 * → Можно запросить: "все пользователи с ролью SALES"
 * → Более реалистично для production-системы типа Chronos
 * <p>
 * ─────────────────────────────────────────────────────────────────────
 * Роли в контексте Lang GmbH:
 * ─────────────────────────────────────────────────────────────────────
 * ADMIN     — полный доступ, управление пользователями
 * SALES     — Vertrieb: заказы, клиенты, цены, договоры
 * LOGISTICS — Lager: отгрузка, приёмка, статусы оборудования
 * SERVICE   — Werkstatt: техобслуживание, ремонт, Availability
 * <p>
 * В Spring Security: @PreAuthorize("hasRole('SALES')")
 * или @PreAuthorize("hasAnyRole('ADMIN', 'SALES')")
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(
        name = "roles",
        indexes = {
                @Index(name = "idx_role_user_id", columnList = "user_id"),
                @Index(name = "idx_role_type", columnList = "role_type"),
                @Index(name = "idx_role_user_role_unique", columnList = "user_id, role_type", unique = true)
        }
)
public class Role extends BaseEntity {

    // ------ Connection with User -----------------------------------
    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    // ------- RoleType -----------------------------------------------
    /**
     * Роль хранится как строка "ADMIN", "SALES", etc.
     * Spring Security по умолчанию ожидает префикс "ROLE_":
     * hasRole('ADMIN') → ищет "ROLE_ADMIN".
     * <p>
     * Решение: в UserDetailsService при маппинге к GrantedAuthority
     * добавляй префикс: "ROLE_" + role.getRoleType().name()
     * <p>
     * Или используй hasAuthority('ADMIN') без префикса — тогда без "ROLE_".
     */
    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "role_type", nullable = false, length = 50)
    private RoleType roletype;

    // ------ Fabric method --------------------------------------------
    public static Role of(User user, RoleType roleType) {
        Role role = new Role();
        role.user = user;
        role.roletype = roleType;
        return role;
    }
}