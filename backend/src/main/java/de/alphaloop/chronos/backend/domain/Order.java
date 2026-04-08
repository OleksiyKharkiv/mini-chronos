package de.alphaloop.chronos.backend.domain;

import de.alphaloop.chronos.backend.enums.OrderStatus;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Order entity - central business entity with complex lifecycle.
 * <p>
 * Key patterns:
 * 1. @Version - optimistic locking for concurrent modifications
 * 2. @OneToMany with orphanRemoval - items are part of order aggregate
 * 3. CascadeType.ALL for items - order controls item lifecycle
 * 4. @PrePersist/@PreUpdate - business logic hooks
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "orders",
        indexes = {
                @Index(name = "idx_order_project_id", columnList = "project_id"),
                @Index(name = "idx_order_status", columnList = "status"),
                @Index(name = "idx_order_number", columnList = "order_number", unique = true),
                @Index(name = "idx_order_dates", columnList = "start_date, end_date")
        }
)
public class Order extends BaseEntity {
    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

// ── Бизнес-поля ───────────────────────────────────────────────────────────

    /**
     * Уникальный номер заказа для клиента: ORD-2026-00000001.
     * Генерируется в @PrePersist, поэтому nullable=false, но insertable через хук.
     * updatable=false: номер заказа никогда не меняется после создания.
     */

    @Column(name = "order_number", nullable = false, updatable = false, length = 20)
    private String orderNumber;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status = OrderStatus.DRAFT;

    @NotNull
    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @NotNull
    @Column(name = "end_date")
    private LocalDate endDate;

    /**
     * BigDecimal для денег — НИКОГДА не используй double/float!
     * double: 0.1 + 0.2 = 0.30000000000000004 (IEEE 754 floating point)
     * BigDecimal: точное десятичное хранение, precision=10, scale=2
     */
    @Column(name = "total_amount", precision = 10, scale = 2)
    private BigDecimal totalAmount;

    // ── Optimistic Locking ────────────────────────────────────────────────────

    /**
     * @Version — Hibernate автоматически:
     * 1. Инкрементирует это поле при каждом UPDATE
     * 2. Добавляет "AND version = :expectedVersion" в WHERE-clause
     * 3. Если 0 строк обновлено → бросает OptimisticLockException
     * <p>
     * Это поле НЕ трогаешь руками — только Hibernate!
     */
    @Version
    @Column(nullable = false)
    private Long version = 0L;

    // ── Связь с OrderItem (Order — aggregate root) ────────────────────────────

    /**
     * orphanRemoval=true: если убрать OrderItem из этого списка,
     * Hibernate автоматически выполнит DELETE для него в БД.
     * <p>
     * Пример:
     * order.getItems().remove(item);  // убираем из списка
     * orderRepository.save(order);     // Hibernate выполнит DELETE item
     * <p>
     * БЕЗ orphanRemoval: item останется в БД как "сирота" без order_id — плохо!
     */
    @OneToMany(
            mappedBy = "order",
            fetch = FetchType.LAZY,
            cascade = CascadeType.ALL,
            orphanRemoval = true
    )
    private List<OrderItem> items = new ArrayList<>();

// ── Lifecycle хуки ────────────────────────────────────────────────────────

    /**
     * @PrePersist вызывается Hibernate ПЕРЕД первым INSERT.
     * К этому моменту ID уже присвоен (SEQUENCE strategy присваивает ID
     * до INSERT, в отличие от IDENTITY).
     */
    @PrePersist
    protected void generatedOrderNumber() {
        if (this.orderNumber == null) {
            this.orderNumber = String.format("ORD-%d-%08d",
                    startDate.getYear(),
                    getId() != null ? getId() : 0L);
        }
    }

    // ── Helper методы для управления коллекцией items ─────────────────────────
    public void addItem(OrderItem item) {
        items.add(item);
        item.setOrder(null);
    }

    public void removeItem(OrderItem item) {
        items.remove(item);
        item.setOrder(null);
    }
// ── Бизнес-методы (Rich Domain Model) ────────────────────────────────────

    /**
     * Подтверждение заказа.
     * Проверяем допустимость перехода статуса — нельзя подтвердить уже отменённый заказ.
     */
    public void confirm() {
        if (this.status != OrderStatus.DRAFT) {
            throw new IllegalStateException("Only DRAFT orders can be confirmed");
        }
        this.status = OrderStatus.CONFIRMED;
    }

    public void cancel() {
        if (this.status == OrderStatus.COMPLETED || this.status == OrderStatus.INVOICED) {
            throw new IllegalStateException("Cannot cancel order with status " + this.status
            );
        }
        this.status = OrderStatus.CANCELED;
    }
    /**
     * Пересчёт суммы заказа на основе позиций.
     * Вызывается сервисом перед сохранением.
     */
    public void recalculateTotalAmount(){
        this.totalAmount = items.stream()
                .map(OrderItem::getLineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}