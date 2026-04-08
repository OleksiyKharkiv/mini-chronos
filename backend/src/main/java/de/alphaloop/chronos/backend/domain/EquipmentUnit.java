package de.alphaloop.chronos.backend.domain;

import de.alphaloop.chronos.backend.enums.EquipmentStatus;
import de.alphaloop.chronos.backend.enums.EquipmentType;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * EquipmentUnit — единица оборудования (Gerät/Artikel).
 * <p>
 * В реальном Chronos у Lang GmbH: проекторы, экраны, LED-стены,
 * звуковые системы — всё, что сдаётся в аренду на мероприятия.
 * <p>
 * ─────────────────────────────────────────────────────────────────────
 * КЛЮЧЕВЫЕ ПАТТЕРНЫ:
 * ─────────────────────────────────────────────────────────────────────
 * <p>
 * 1. SKU (Stock Keeping Unit) — артикул оборудования:
 * Уникальный бизнес-идентификатор, в отличие от технического ID.
 * Пример: "PROJ-EPSON-EB-L1755U-001"
 * В реальных системах часто используется как barcode для сканирования.
 * <p>
 * 2. EquipmentType как Enum (EnumType.STRING):
 * Позволяет делать эффективные запросы:
 * WHERE eu.type = 'PROJECTOR' — индекс работает отлично.
 * В Chronos: Projektor, Leinwand, LED-Wall, Audio, Lighting...
 * <p>
 * 3. status (AVAILABLE / RENTED / MAINTENANCE / RETIRED):
 * НЕ вычисляется динамически из Availability!
 * Почему? Производительность: SELECT COUNT(*) FROM availability WHERE ...
 * на 300.000 Geräten на каждую страницу — катастрофа.
 * Статус обновляется сервисом при аренде/возврате/сдаче в ремонт.
 * <p>
 * 4. Связь @OneToMany с Availability (не с OrderItem напрямую):
 * Equipment → Availability → OrderItem
 * Это позволяет также блокировать оборудование БЕЗ заказа
 * (например, на техобслуживание — Availability без order_item_id).
 * <p>
 * 5. dailyRate — базовая цена за день:
 * Используется как источник для "price snapshot" в OrderItem.
 * Изменение dailyRate НЕ влияет на уже созданные заказы.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "equipment_units",
        indexes = {
                @Index(name = "idx_equipment_sku", columnList = "sku", unique = true),
                @Index(name = "idx_equipment_type", columnList = "type"),
                @Index(name = "idx_equipment_status", columnList = "status"),
                // Составной индекс для частого запроса "доступные проекторы":
                @Index(name = "idx_equipment_type_status", columnList = "type, status")
        })
public class EquipmentUnit extends BaseEntity {
    @NotBlank
    @Size(max = 100)
    @Column(nullable = false, unique = true, length = 100)
    private String sku;

    @Size(max = 1000)
    @Column(length = 1000)
    private String description;

    // ── Классификация ─────────────────────────────────────────────────────────

    /**
     * Тип оборудования — для поиска и фильтрации.
     * EnumType.STRING обязателен (см. комментарий в Order.java о ORDINAL vs STRING).
     */
    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private EquipmentType type; // Projektor, Leinwand, LED-Wall, Audio, Lighting...

    // ── Статус ────────────────────────────────────────────────────────────────
    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EquipmentStatus status = EquipmentStatus.AVAILABLE;

    // ── Ценообразование ───────────────────────────────────────────────────────

    /**
     * Базовая дневная ставка аренды.
     * Используется как default при создании OrderItem (price snapshot).
     * precision=10, scale=2: максимум 99 999 999,99 €
     */
    @Column(name = "daily_rate", precision = 10, scale = 2)
    private BigDecimal dailyRate;

    // ── Связи ─────────────────────────────────────────────────────────────────

    /**
     * История и текущие брони через Availability.
     * FetchType.LAZY: при загрузке equipment НЕ тянем все его записи занятости.
     * <p>
     * Нет CascadeType.REMOVE: удаление equipment не должно удалять историю аренды!
     * (финансовая история должна сохраняться — это требование аудита/бухгалтерии)
     */
    @OneToMany(
            mappedBy = "equipment",
            fetch = FetchType.LAZY,
            cascade = {CascadeType.PERSIST, CascadeType.MERGE}
    )
    private List<Availability> availabilityRecords = new ArrayList<>();

    // ── Бизнес-методы ─────────────────────────────────────────────────────────

    public boolean isAvailable() {
        return this.status == EquipmentStatus.AVAILABLE;
    }
    public void markAsRented() {
        if (this.status != EquipmentStatus.AVAILABLE) {
            throw new IllegalStateException("Equipment " + sku + " is not available for rent." + this.status);
        }
        this.status = EquipmentStatus.RENTED;
    }
    public void markAsAvailable() {
        this.status = EquipmentStatus.AVAILABLE;
    }
    public void sendToMaintenance(){
        this.status = EquipmentStatus.MAINTENANCE;
    }
    public @NotNull BigDecimal getDailyRate() {
        return dailyRate == null ? BigDecimal.ZERO : dailyRate;
    }
}