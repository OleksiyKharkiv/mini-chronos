package de.alphaloop.chronos.backend.mapper;

import de.alphaloop.chronos.backend.domain.Project;
import de.alphaloop.chronos.backend.dto.request.ProjectCreateRequest;
import de.alphaloop.chronos.backend.dto.request.ProjectUpdateRequest;
import de.alphaloop.chronos.backend.dto.response.ProjectDetailResponse;
import de.alphaloop.chronos.backend.dto.response.ProjectResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

import java.util.List;

/**
 * ProjectMapper — demonstrates MapStruct's most important feature:
 * mapping NESTED / RELATED objects with dot-notation paths.
 *
 * The challenge: ProjectResponse has flat fields "customerId" and "customerName",
 * but the Project entity has a nested object: project.customer.id and project.customer.name.
 *
 * MapStruct solves this with @Mapping(source = "customer.id", target = "customerId").
 * The dot-notation traverses the object graph automatically.
 *
 * HOW DOES MAPSTRUCT HANDLE LAZY LOADING?
 *
 * Important: if project.customer is a Hibernate LAZY proxy and the session is closed,
 * accessing project.getCustomer().getName() triggers LazyInitializationException.
 *
 * Solution: ALWAYS call the mapper INSIDE an active transaction, or ensure
 * the data was loaded via EntityGraph BEFORE the transaction closes.
 *
 * In our architecture:
 *   - Service loads Project with EntityGraph (customer pre-loaded) → mapper called in controller
 *   - The transaction is closed BEFORE the controller calls the mapper
 *   - But customer is already loaded (not a proxy anymore) → safe to access
 *
 * This is why ProjectRepository.findById() overrides with @EntityGraph("customer"):
 * it ensures customer is ALWAYS loaded when getting a project by ID.
 */
@Mapper(componentModel = "spring", uses = {OrderMapper.class})
public interface ProjectMapper {

    /**
     * Nested source paths with dot-notation.
     *
     * @Mapping(source = "customer.id", target = "customerId"):
     *   Read project.getCustomer().getId() → write to ProjectResponse.customerId()
     *
     * @Mapping(source = "customer.name", target = "customerName"):
     *   Read project.getCustomer().getName() → write to ProjectResponse.customerName()
     *
     * Without these @Mapping annotations, MapStruct would look for a field
     * "customerId" directly on Project — it doesn't exist → compilation error.
     * The dot-notation is explicit and type-safe.
     */
    @Mapping(source = "customer.id",   target = "customerId")
    @Mapping(source = "customer.name", target = "customerName")
    ProjectResponse toResponse(Project project);

    /**
     * Detail response: same nested mappings + orders list.
     * OrderMapper (in 'uses') handles each Order → OrderResponse conversion.
     *
     * MapStruct automatically applies toResponse(Order) from OrderMapper
     * for each element in project.orders → List<OrderResponse>.
     */
    @Mapping(source = "customer.id",   target = "customerId")
    @Mapping(source = "customer.name", target = "customerName")
    ProjectDetailResponse toDetailResponse(Project project);

    List<ProjectResponse> toResponseList(List<Project> projects);

    /**
     * Create: ignore customer (set separately in the service after loading the Customer entity).
     *
     * WHY ignore customer in the mapper?
     * ProjectCreateRequest has customerId (Long), not a Customer object.
     * We can't map a Long → Customer here — that would require a DB lookup.
     * DB lookups don't belong in mappers. Mappers are pure data transformation.
     *
     * The service flow:
     *   1. mapper.toEntity(request) → Project with customer=null
     *   2. Customer customer = customerService.getById(request.customerId())
     *   3. project.setCustomer(customer)
     *   4. projectRepository.save(project)
     *
     * Alternatively: @Mapping(target = "customer.id", source = "customerId")
     * creates a Customer proxy with just the id. This works for FK insert
     * but skips the "does this customer exist?" check. The explicit approach is safer.
     */
    @Mapping(target = "id",          ignore = true)
    @Mapping(target = "customer",    ignore = true)   // set in service
    @Mapping(target = "status",      ignore = true)   // always DRAFT on creation
    @Mapping(target = "orders",      ignore = true)
    @Mapping(target = "createdAt",   ignore = true)
    @Mapping(target = "updatedAt",   ignore = true)
    Project toEntity(ProjectCreateRequest request);

    @Mapping(target = "id",          ignore = true)
    @Mapping(target = "customer",    ignore = true)
    @Mapping(target = "status",      ignore = true)   // use transitionStatus() for status changes
    @Mapping(target = "orders",      ignore = true)
    @Mapping(target = "createdAt",   ignore = true)
    @Mapping(target = "updatedAt",   ignore = true)
    void updateEntity(ProjectUpdateRequest request, @MappingTarget Project existing);
}