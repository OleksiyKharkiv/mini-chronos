package de.alphaloop.chronos.backend.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * OrderItem — позиция заказа (Auftragsposition).
 * <p>
 * Представляет конкретную единицу оборудования в заказе:
 * "2 проектора Epson EB-L1755U с 13 по 14 апреля по 450€/день"
 * <p>
 * ─────────────────────────────────────────────────────────────────────
 * КЛЮЧЕВЫЕ ПАТТЕРНЫ:
 * ─────────────────────────────────────────────────────────────────────
 * <p>
 * 1. Связь с Order (many-to-one, owning side):
 * OrderItem — это "дочерняя" сущность. FK order_id находится в этой таблице.
 * FetchType.LAZY — не загружать весь Order при загрузке одной позиции.
 * <p>
 * 2. Связь с EquipmentUnit (many-to-one):
 * Одно оборудование может быть в разных заказах (в разные даты).
 * НО: в одно и то же время — только в одном заказе (проверяет Availability).
 * <p>
 * 3. unitPrice vs EquipmentUnit.dailyRate:
 * ВАЖНО: unitPrice хранится в OrderItem (не берётся из equipment.dailyRate)!
 * Почему? Цена могла измениться после создания заказа.
 * Это называется "price snapshot" — снимок цены на момент заказа.
 * Классический паттерн для любой торговой/rental системы.
 * <p>
 * 4. getLineTotal() — вычисляемое поле:
 * Не храним в БД (нет @Column), вычисляем на лету.
 * Альтернатива: @Formula("quantity * unit_price") — Hibernate вычислит в SQL.
 * Для MVP достаточно Java-метода.
 * <p>
 * 5. Нет @Version здесь:
 * OrderItem не редактируется конкурентно напрямую — только через Order.
 * Optimistic locking контролируется на уровне Order (aggregate root).
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "order_items",
        indexes = {
                @Index(name = "idx_order_item_order_id", columnList = "order_id"),
                @Index(name = "idx_order_item_equipment_id", columnList = "equipment_id")
        }
)
public class OrderItem extends BaseEntity {
// ── Связь с Order (owning side — FK в этой таблице) ───────────────────────

    /**
     * optional = false: order_id NOT NULL в БД + INNER JOIN в запросах (быстрее LEFT JOIN).
     * FetchType.LAZY: при загрузке позиции не тянем весь заказ со всеми полями.
     */
    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    // ── Связь с EquipmentUnit ─────────────────────────────────────────────────
    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "equipment_id", nullable = false)
    private EquipmentUnit equipment;

    // ── Бизнес-поля ───────────────────────────────────────────────────────────

    /**
     * Количество единиц оборудования.
     *
     * @Min(1): нельзя заказать 0 или отрицательное количество.
     * В БД: CHECK (quantity >= 1) — добавить в Liquibase changeSet!
     */
    @Min(1)
    @Column(name = "quantity", nullable = false)
    private int quantity;

    /**
     * "Price snapshot" — цена на момент создания заказа.
     * НЕ ссылка на equipment.dailyRate! Та цена могла измениться.
     * <p>
     * СЦЕНАРИЙ: Заказ создан в январе по 450€/день.
     * В феврале цена изменена на 500€/день.
     * Старый заказ должен оставаться по 450€ — иначе несоответствие в бухгалтерии!
     */
    @NotNull
    @Column(name = "unit_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal unitPrice;

    public BigDecimal getLineTotal() {
        if (unitPrice == null) return BigDecimal.ZERO;
        return unitPrice.multiply(BigDecimal.valueOf(quantity));
    }

    // ── Конструктор для удобного создания ─────────────────────────────────────
    /**
     * Фабричный конструктор — создаёт корректно связанную позицию.
     * Обрати внимание: unitPrice копируется из equipment.getDailyRate()
     * именно здесь, при создании — это и есть "price snapshot".
     */
    public static OrderItem of (Order order, EquipmentUnit equipment, int quantity){
        OrderItem item = new OrderItem();
        item.order = order;
        item.equipment = equipment;
        item.quantity = quantity;
        item.unitPrice = equipment.getDailyRate();
        return item;
    }
}