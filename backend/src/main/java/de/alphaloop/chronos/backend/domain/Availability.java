package de.alphaloop.chronos.backend.domain;


import de.alphaloop.chronos.backend.enums.AvailabilityStatus;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

/**
 * Availability — запись о занятости оборудования (Verfügbarkeit).
 * <p>
 * Это "сердце" rental-системы. Каждая запись говорит:
 * "Оборудование X недоступно с даты A по дату B по причине Y"
 * <p>
 * Примеры записей:
 * equipment=Epson#3, start=2026-04-13, end=2026-04-14, status=RESERVED, orderItem=1234
 * equipment=Epson#3, start=2026-04-20, end=2026-04-22, status=MAINTENANCE, orderItem=null
 * <p>
 * ─────────────────────────────────────────────────────────────────────
 * КЛЮЧЕВЫЕ ПАТТЕРНЫ:
 * ─────────────────────────────────────────────────────────────────────
 * <p>
 * 1. Nullable связь с OrderItem:
 * Availability может существовать БЕЗ OrderItem!
 * Причины: техобслуживание, резервирование администратором,
 * потеря/повреждение оборудования.
 * Поэтому order_item_id = NULLABLE FK.
 * <p>
 * 2. Проверка пересечения дат (date overlap):
 * КРИТИЧНО для rental-бизнеса! Два заказа не могут иметь одно
 * оборудование в пересекающийся период.
 * <p>
 * SQL для проверки (используется в AvailabilityRepository):
 * SELECT COUNT(*) FROM availability
 * WHERE equipment_id = :equipmentId
 * AND start_date < :requestedEnd
 * AND end_date > :requestedStart
 * AND status != 'CANCELLED'
 * <p>
 * Это стандартная формула проверки пересечения дат:
 * [A, B] пересекается с [C, D] ↔ A < D AND B > C
 * <p>
 * 3. Индексы на (equipment_id, start_date, end_date):
 * В production у Lang GmbH — тысячи записей на каждое оборудование.
 * Без индекса: полный скан таблицы на КАЖДУЮ проверку доступности.
 * С индексом: B-Tree lookup за O(log n).
 * В Liquibase changeSet обязательно добавить этот составной индекс!
 * <p>
 * 4. В PostgreSQL для production используют daterange тип:
 * Колонка daterange + GiST индекс + оператор && (overlap).
 * Это ещё быстрее, но сложнее в JPA-маппинге.
 * Для MVP достаточно двух отдельных DATE колонок + стандартного индекса.
 * <p>
 * 5. Нет @Version:
 * Availability создаётся и удаляется транзакционно вместе с OrderItem.
 * Concurrent access контролируется на уровне Order (@Version там есть).
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "availability",
        indexes = {
                @Index(
                        name = "idx_availability_equipment_dates",
                        columnList = "equipment_id, start_date, end_date"
                ),
                @Index(name = "idx_availability_order_itemid", columnList = "order_item_id"),
                @Index(name = "idx_availability_status", columnList = "status")
        })
public class Availability extends BaseEntity {
    // ── Связь с оборудованием ─────────────────────────────────────────────────

    /**
     * Обязательная связь: каждая запись занятости относится к конкретному оборудованию.
     * optional = false → NOT NULL + INNER JOIN в запросах.
     */
    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "equipment_id", nullable = false)
    private EquipmentUnit equipment;

    // ── Необязательная связь с позицией заказа ───────────────────────────────

    /**
     * Nullable: запись занятости может быть без заказа (техобслуживание и т.д.).
     *
     * @ManyToOne(optional = true): Hibernate генерирует LEFT JOIN (не INNER).
     * nullable = true: в БД это NULL-able Foreign Key.
     * <p>
     * CascadeType: нет cascade — Availability не управляет OrderItem.
     * Жизненный цикл: OrderItem создаётся → сервис создаёт Availability.
     * Orde  rItem удаляется → сервис удаляет Availability.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "order_item_id", nullable = true)
    private OrderItem orderItem;

    // ── Период занятости ─────────────────────────────────────────────────────
    @NotNull
    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;
    @NotNull
    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    // ── Статус занятости ─────────────────────────────────────────────────────

    /**
     * Статус объясняет ПРИЧИНУ занятости:
     * - RESERVED: забронировано под конкретный заказ
     * - MAINTENANCE: техобслуживание (без заказа)
     * - BLOCKED: заблокировано администратором вручную
     * - CANCELLED: отменённая запись (сохраняем историю, не удаляем!)
     * <p>
     * ПОЧЕМУ СОХРАНЯЕМ ОТМЕНЁННЫЕ?
     * Финансовый аудит: нужно знать, что оборудование планировалось на дату,
     * потом бронь отменили. Это важно для разрешения споров с клиентами.
     */
    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AvailabilityStatus status = AvailabilityStatus.RESERVED;

    // ── Фабричный метод ───────────────────────────────────────────────────────

    /**
     * Создание брони для заказа. Используется в OrderService при подтверждении заказа.
     */
    public static Availability reserveForOrderItem(
            EquipmentUnit equipment,
            OrderItem orderItem,
            LocalDate startDate,
            LocalDate endDate
    ) {
        Availability availability = new Availability();
        availability.equipment = equipment;
        availability.orderItem = orderItem;
        availability.startDate = startDate;
        availability.endDate = endDate;
        availability.status = AvailabilityStatus.RESERVED;
        return availability;
    }

    /**
     * Создание блокировки на техобслуживание (без заказа).
     */
    public static Availability blockForMaintenance(
            EquipmentUnit equipment,
            LocalDate startDate,
            LocalDate endDate) {
        Availability availability = new Availability();
        availability.equipment = equipment;
        availability.orderItem = null;
        availability.startDate = startDate;
        availability.endDate = endDate;
        availability.status = AvailabilityStatus.MAINTENANCE;
        return availability;
    }

    // ── Бизнес-метод ─────────────────────────────────────────────────────────

    /**
     * Проверка пересечения с запрошенным периодом.
     * Формула: [A,B] ∩ [C,D] ≠ ∅  ↔  A < D  AND  B > C
     * <p>
     * Используется для валидации в памяти (unit-тесты, дополнительная проверка).
     * Для запросов к БД используй SQL в репозитории — это быстрее!
     */
    public boolean overlaps(
            LocalDate requestedStart,
            LocalDate requestedEnd) {
        return this.startDate.isBefore(requestedEnd)
                && endDate.isAfter(requestedStart);
    }
}