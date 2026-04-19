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

/**
 * User — системный пользователь (Mitarbeiter bei Lang GmbH).
 *
 * В отличие от Customer (внешний клиент), User — это сотрудник,
 * работающий в системе: менеджер продаж, логист, техник склада.
 *
 * ИСПРАВЛЕННЫЕ БАГИ:
 * 1. Удалён `import org.jspecify.annotations.Nullable`:
 *    - jspecify — внешняя библиотека, не задекларирована в pom.xml
 *    - Jakarta Validation (@NotNull, @Nullable) уже в проекте
 *    - Для nullable параметров в Java достаточно Optional<T> или комментария
 *
 * 2. Переименован метод `setPassword_hash()` → `setPasswordHash()`:
 *    - Java naming convention: camelCase для методов
 *    - snake_case — это SQL/Python соглашение, не Java
 *    - Нарушение вызвало бы проблемы с Lombok (Lombok генерирует setPasswordHash,
 *      а у нас был дублирующий setPassword_hash — путаница при вызове)
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(
        name = "users",
        indexes = {
                @Index(name = "idx_user_email",    columnList = "email",    unique = true),
                @Index(name = "idx_user_username", columnList = "username", unique = true),
                @Index(name = "idx_user_active",   columnList = "active")
        }
)
public class User extends BaseEntity {

    /**
     * Логин для входа в систему.
     * Примеры: "m.mueller", "j.schmidt" (типичный корпоративный формат).
     * updatable=true: пользователь может сменить username (редко, но возможно).
     */
    @NotBlank
    @Size(max = 100)
    @Column(name = "username", nullable = false, unique = true, length = 100)
    private String userName;

    @NotBlank
    @Email
    @Size(max = 255)
    @Column(nullable = false, unique = true)
    private String email;

    /**
     * ТОЛЬКО BCrypt hash — НИКОГДА не храни пароль в открытом виде!
     * BCrypt hash: всегда 60 символов (независимо от длины пароля).
     * Пример: "$2a$12$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy"
     *
     * Поле называется passwordHash (не password) — явно показывает что это хэш.
     * Это хорошая практика: меньше шансов случайно залогировать или вернуть в API.
     */
    @NotBlank
    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @NotBlank
    @Column(name = "first_name", nullable = false, length = 100)
    private String firstName;

    @NotBlank
    @Column(name = "last_name", nullable = false, length = 100)
    private String lastName;

    @Column(nullable = false)
    private boolean active = true;

    // ── Roles ──────────────────────────────────────────────────────────────

    /**
     * CascadeType.ALL + orphanRemoval=true:
     * - User — aggregate root для своих ролей
     * - При сохранении нового User сразу сохраняются его Role-записи
     * - user.removeRole(role) → Hibernate выполнит DELETE для Role в БД
     *
     * FetchType.LAZY + предупреждение для Spring Security:
     * Роли нужны при каждой аутентификации. Если загружаешь User через
     * стандартный findById() — роли не загрузятся (LAZY).
     * В UserDetailsService используй: findByUserNameWithRoles() через @EntityGraph!
     * Иначе: LazyInitializationException при проверке hasRole() в фильтре.
     */
    @OneToMany(
            mappedBy = "user",
            cascade = CascadeType.ALL,
            orphanRemoval = true,
            fetch = FetchType.LAZY
    )
    private List<Role> roles = new ArrayList<>();

    // ── Helper-методы ──────────────────────────────────────────────────────

    public void addRole(Role role) {
        roles.add(role);
        role.setUser(this);
    }

    public void removeRole(Role role) {
        roles.remove(role);
        role.setUser(null);
    }

    /**
     * Отображаемое имя: "Max Müller" или username если имя не задано.
     * Используется в UI и логах — не для аутентификации!
     */
    public String getDisplayName() {
        if (firstName != null && !firstName.isBlank()) {
            return firstName + " " + lastName;
        }
        return userName;
    }

    /**
     * Устанавливает BCrypt-хэш пароля.
     *
     * ИСПРАВЛЕНО: метод переименован из setPassword_hash() в setPasswordHash().
     * Java Naming Convention: методы — camelCase.
     * snake_case в именах методов — ошибка, которую замечают на code review сразу.
     *
     * Вызывается только из AuthService после BCryptPasswordEncoder.encode(rawPassword).
     * НИКОГДА не вызывай этот метод с plaintext паролем!
     */
    public void setPasswordHash(String bcryptHash) {
        this.passwordHash = bcryptHash;
    }
}