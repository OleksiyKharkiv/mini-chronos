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
 * Customer — клиент компании (Stammdaten).
 *
 * В контексте Lang GmbH: организации, арендующие оборудование для мероприятий.
 * Например: "Messe Köln GmbH", "BMW AG", "Universität Köln".
 *
 * ИСПРАВЛЕННЫЕ БАГИ:
 * - Удалён `import jdk.jfr.Name` — JDK-internal класс системы мониторинга (JFR),
 *   не имеет ничего общего с JPA. Вероятно попал через автоимпорт IDE.
 *   Компилируется, но создаёт зависимость от внутреннего JDK API — плохая практика.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(
        name = "customers",
        indexes = {
                @Index(name = "idx_customer_name",   columnList = "name"),
                @Index(name = "idx_customer_email",  columnList = "email",  unique = true),
                @Index(name = "idx_customer_active", columnList = "active")
        }
)
public class Customer extends BaseEntity {

    @NotBlank
    @Size(max = 255)
    @Column(nullable = false)
    private String name;

    /**
     * @Email: валидация формата email на уровне Jakarta Validation.
     * Уникальность гарантируется и через @Column(unique=true) в JPA
     * и через Liquibase (idx_customer_email с unique=true).
     * Два уровня защиты: приложение + БД.
     */
    @NotBlank
    @Size(max = 255)
    @Email
    @Column(nullable = false, unique = true)
    private String email;

    @Size(max = 50)
    @Column(name = "phone_number")
    private String phone;

    @Column(nullable = false)
    private boolean active = true;  // новые клиенты активны по умолчанию

    /**
     * @OneToMany(mappedBy = "customer"):
     * - Customer — обратная (inverse) сторона связи.
     * - FK customer_id хранится в таблице projects (не здесь).
     * - mappedBy указывает на поле в Project, которое владеет FK.
     *
     * cascade = {PERSIST, MERGE}: без REMOVE!
     * - PERSIST: сохранение нового Customer сохраняет его новые Projects.
     * - MERGE: обновление Customer обновляет его изменённые Projects.
     * - REMOVE намеренно отсутствует: удаление клиента не должно удалять
     *   проекты — у них финансовая история, важная для бухгалтерии.
     */
    @OneToMany(
            mappedBy = "customer",
            fetch = FetchType.LAZY,
            cascade = {CascadeType.PERSIST, CascadeType.MERGE}
    )
    private List<Project> projects = new ArrayList<>();

    // ── Helper-методы для управления двунаправленной связью ───────────────────

    /**
     * Всегда управляй обеими сторонами двунаправленной связи!
     * Если только projects.add(project) — Hibernate в памяти знает о связи,
     * но project.customer останется null до перезагрузки из БД.
     * Это приводит к NPE и багам при работе в той же транзакции.
     */
    public void addProject(Project project) {
        projects.add(project);
        project.setCustomer(this);
    }

    public void removeProject(Project project) {
        projects.remove(project);
        project.setCustomer(null);
    }
}