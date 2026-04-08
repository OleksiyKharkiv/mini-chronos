package de.alphaloop.chronos.backend.domain;

import jakarta.persistence.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Abstract base class for all JPA entities in mini-chronos.
 *
 * ─────────────────────────────────────────────────────────────────────
 * DESIGN DECISIONS (обязательно знай это для разговора с Себастианом):
 * ─────────────────────────────────────────────────────────────────────
 *
 * 1. @MappedSuperclass (не @Entity):
 *    - Hibernate не создаёт отдельную таблицу для BaseEntity
 *    - Поля id, createdAt, updatedAt "вливаются" в таблицу каждого наследника
 *    - Альтернатива: @Inheritance(TABLE_PER_CLASS) — создаёт таблицу, но медленнее
 *    - В ERP всегда используй @MappedSuperclass для base-полей
 *
 * 2. @EntityListeners(AuditingEntityListener.class):
 *    - Spring Data JPA автоматически заполняет @CreatedDate и @LastModifiedDate
 *    - ВАЖНО: для работы нужна аннотация @EnableJpaAuditing на @Configuration классе!
 *    - Без неё поля будут null — частая ошибка у джунов
 *
 * 3. GenerationType.SEQUENCE (не IDENTITY, не AUTO):
 *    - IDENTITY: PostgreSQL использует SERIAL/BIGSERIAL, но Hibernate
 *      не может делать batch inserts (производительность падает)
 *    - SEQUENCE: Hibernate заранее резервирует allocationSize=50 ID,
 *      делает INSERT-ы батчами — критично для ERP с тысячами записей
 *    - allocationSize=50: Hibernate берёт из sequence числа по 50 штук,
 *      чтобы не делать SELECT nextval() на каждый INSERT
 *
 * 4. equals/hashCode на основе ID:
 *    - Hibernate при lazy loading создаёт прокси-объект (подкласс сущности)
 *    - Прокси знает только ID, остальные поля загрузятся лишь при обращении
 *    - Если сравнивать прокси с реальным объектом по полям — equals вернёт false
 *    - getClass() != o.getClass(): у Customer и User разные классы,
 *      хотя оба extends BaseEntity — instanceof дал бы true (неверно!)
 *
 * ИСПРАВЛЕННЫЕ БАГИ (по сравнению с исходным файлом):
 * - БАГ #1: sequenceName = "default_sequence, allocationSize = 50"
 *   Закрывающая кавычка была внутри строки — allocationSize игнорировался,
 *   использовалось значение по умолчанию (1), что означало SELECT nextval()
 *   на КАЖДЫЙ INSERT. При 1000 заказах = 1000 лишних запросов к БД.
 * - БАГ #2: Блок комментария /* ... * / был за пределами класса — перенесён внутрь.
 */
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "base_seq")
    @SequenceGenerator(
            name = "base_seq",
            sequenceName = "default_sequence",   // <-- ИСПРАВЛЕНО: кавычка закрыта здесь
            allocationSize = 50                  // <-- ИСПРАВЛЕНО: теперь это отдельный параметр
    )
    private Long id;

    @CreatedDate
    @Column(nullable = false, updatable = false)  // updatable=false: createdAt никогда не меняется
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    // ── Getters (без setters для id/createdAt — они не должны меняться вручную) ──

    public Long getId() {
        return id;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    // ── equals/hashCode ────────────────────────────────────────────────────────

    /**
     * CRITICAL: equals/hashCode на основе ID для корректной работы с Hibernate Proxy.
     *
     * Сценарий проблемы без этого подхода:
     *   Order order = orderRepo.findById(1L);          // реальный объект
     *   Order proxy = em.getReference(Order.class, 1L); // прокси (только ID заполнен)
     *   order.equals(proxy) // → false, если сравниваем все поля!
     *   order.equals(proxy) // → true, если сравниваем только ID ✓
     *
     * getClass() вместо instanceof:
     *   Customer customer = new Customer();
     *   User user = new User();
     *   // instanceof вернул бы true (оба BaseEntity) — НЕВЕРНО
     *   // getClass() вернёт false — ВЕРНО
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
        // Фиксированное значение 31 для новых объектов (id == null):
        // позволяет хранить в HashSet до сохранения в БД.
        // После сохранения hashCode меняется — НЕ храни новые entity в HashSet до persist!
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "{id=" + id + "}";
    }
}