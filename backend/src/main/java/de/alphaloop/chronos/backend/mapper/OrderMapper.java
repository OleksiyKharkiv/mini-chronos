package de.alphaloop.chronos.backend.mapper;

import de.alphaloop.chronos.backend.domain.Order;
import de.alphaloop.chronos.backend.domain.OrderItem;
import de.alphaloop.chronos.backend.dto.response.OrderDetailResponse;
import de.alphaloop.chronos.backend.dto.response.OrderItemResponse;
import de.alphaloop.chronos.backend.dto.response.OrderResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/**
 * OrderMapper — demonstrates the most advanced MapStruct features:
 *
 *   1. Two-level nested path mapping (order.project.customer.name)
 *   2. expression() for calling a method on the source entity
 *   3. Implicit sub-mapper (OrderItem → OrderItemResponse inside Order mapping)
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * TWO-LEVEL NESTED PATH: order.project.customer.name → customerName
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * The response DTO has a flat field "customerName".
 * The path in the entity is: order → project → customer → name (3 hops).
 *
 * @Mapping(source = "project.customer.name", target = "customerName")
 * MapStruct generates:
 *   String customerName = null;
 *   if (order.getProject() != null
 *       && order.getProject().getCustomer() != null
 *       && order.getProject().getCustomer().getName() != null) {
 *       customerName = order.getProject().getCustomer().getName();
 *   }
 *
 * MapStruct generates null-safe traversal automatically — no NPE even if
 * project or customer is null (e.g. in a unit test with a partial entity).
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * expression() FOR COMPUTED FIELDS
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * OrderItemResponse has "lineTotal" — a computed field (quantity × unitPrice).
 * It's not a field on OrderItem, it's a method: item.getLineTotal().
 * MapStruct cannot discover it automatically (it's not a standard getter pattern
 * because the field doesn't exist in the entity, only the computed result).
 *
 * expression = "java(item.getLineTotal())" — inline Java expression.
 * MapStruct injects it verbatim into the generated code:
 *   BigDecimal lineTotal = item.getLineTotal();
 *
 * Use expression() sparingly — it bypasses compile-time type checking.
 * But for a simple method call, it's the cleanest solution.
 */
@Mapper(componentModel = "spring")
public interface OrderMapper {

    /**
     * Lean order response for lists.
     * Three-level traversal for customerName: project → customer → name.
     */
    @Mapping(source = "project.id",              target = "projectId")
    @Mapping(source = "project.name",            target = "projectName")
    @Mapping(source = "project.customer.name",   target = "customerName")
    OrderResponse toResponse(Order order);

    /**
     * Rich order response for detail page.
     * Additional fields: version (for optimistic locking), customerId, items list.
     *
     * "items" → List<OrderItemResponse>: MapStruct calls toItemResponse() for each element.
     * No explicit @Mapping needed for items if the field name matches — MapStruct
     * finds toItemResponse(OrderItem) in this same mapper and uses it automatically.
     */
    @Mapping(source = "project.id",              target = "projectId")
    @Mapping(source = "project.name",            target = "projectName")
    @Mapping(source = "project.customer.id",     target = "customerId")
    @Mapping(source = "project.customer.name",   target = "customerName")
    OrderDetailResponse toDetailResponse(Order order);

    List<OrderResponse> toResponseList(List<Order> orders);

    /**
     * OrderItem → OrderItemResponse.
     *
     * equipment.sku and equipment.name: nested source paths (one level).
     * lineTotal: expression() because it's a computed method, not a field.
     *
     * This method is also used by toDetailResponse() implicitly:
     * MapStruct sees Order.items (List<OrderItem>) → OrderDetailResponse.items (List<OrderItemResponse>)
     * and uses this method for each element automatically.
     */
    @Mapping(source = "equipment.id",   target = "equipmentId")
    @Mapping(source = "equipment.sku",  target = "equipmentSku")
    @Mapping(source = "equipment.name", target = "equipmentName")
    @Mapping(target = "lineTotal", expression = "java(item.getLineTotal())")
    OrderItemResponse toItemResponse(OrderItem item);
}