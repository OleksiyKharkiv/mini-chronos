package de.alphaloop.chronos.backend.mapper;

import de.alphaloop.chronos.backend.domain.Customer;
import de.alphaloop.chronos.backend.dto.request.CustomerCreateRequest;
import de.alphaloop.chronos.backend.dto.request.CustomerUpdateRequest;
import de.alphaloop.chronos.backend.dto.response.CustomerDetailResponse;
import de.alphaloop.chronos.backend.dto.response.CustomerResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

import java.util.List;

/**
 * CustomerMapper — converts between Customer entity and its DTOs.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * WHAT IS MAPSTRUCT AND WHY NOT MODELMAPPER?
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * MapStruct is a CODE GENERATOR — it reads your @Mapper interface at COMPILE TIME
 * and generates a plain Java implementation class (CustomerMapperImpl).
 *
 * Example of what MapStruct generates for toResponse():
 *
 *   @Component
 *   public class CustomerMapperImpl implements CustomerMapper {
 *       @Override
 *       public CustomerResponse toResponse(Customer customer) {
 *           if (customer == null) return null;
 *           Long id = customer.getId();
 *           String name = customer.getName();
 *           String email = customer.getEmail();
 *           String phone = customer.getPhone();
 *           boolean active = customer.isActive();
 *           LocalDateTime createdAt = customer.getCreatedAt();
 *           return new CustomerResponse(id, name, email, phone, active, createdAt);
 *       }
 *   }
 *
 * WHY NOT ModelMapper (which uses reflection)?
 *
 * ModelMapper:
 *   - Works at RUNTIME via reflection
 *   - Mapping errors discovered at RUNTIME → production bugs
 *   - Slow: reflection is 10-100x slower than direct method calls
 *   - Magic: hard to debug when it maps incorrectly
 *
 * MapStruct:
 *   - Works at COMPILE TIME via annotation processing
 *   - Mapping errors discovered at COMPILE TIME → caught before deployment
 *   - Fast: generated code is plain Java, same speed as hand-written
 *   - Transparent: open CustomerMapperImpl.java and see exactly what it does
 *
 * For a Hibernate ERP with hundreds of mapping operations per second
 * (each API request converts entities to DTOs), the performance difference matters.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * @Mapper(componentModel = "spring")
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * componentModel = "spring": the generated CustomerMapperImpl gets @Component.
 * Spring detects it and registers it as a bean.
 * You inject it like any other Spring bean:
 *
 *   @RestController
 *   public class CustomerController {
 *       private final CustomerMapper customerMapper; // injected by Spring
 *   }
 *
 * WITHOUT componentModel = "spring": you'd call Mappers.getMapper(CustomerMapper.class)
 * — a factory method, not Spring-managed. No DI, no @Autowired. Only use this for
 * non-Spring projects.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * uses = {ProjectMapper.class}
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * CustomerDetailResponse contains List<ProjectResponse>.
 * MapStruct needs to know HOW to convert Project → ProjectResponse.
 * By declaring uses = {ProjectMapper.class}, MapStruct delegates the
 * Project → ProjectResponse conversion to ProjectMapper.toResponse().
 *
 * Without uses: MapStruct cannot find a mapping for Project and compilation FAILS.
 * This enforces the single responsibility: each mapper handles its own entity.
 */
@Mapper(componentModel = "spring", uses = {ProjectMapper.class})
public interface CustomerMapper {

    // ── Entity → Response (READ direction) ───────────────────────────────────

    /**
     * Map Customer to lean CustomerResponse (for list views).
     *
     * All field names match (id, name, email, phone, active, createdAt) →
     * MapStruct maps them automatically by name, no @Mapping needed.
     *
     * "projects" field from Customer is NOT in CustomerResponse →
     * MapStruct ignores it automatically (no unmapped target warning because
     * CustomerResponse is a record, not a class with setters).
     */
    CustomerResponse toResponse(Customer customer);

    /**
     * Map Customer to rich CustomerDetailResponse (for detail page).
     *
     * The "projects" field: Customer.projects is List<Project>,
     * CustomerDetailResponse.projects is List<ProjectResponse>.
     * MapStruct uses ProjectMapper.toResponse() for each element (see 'uses' above).
     *
     * updatedAt: present in CustomerDetailResponse but not in CustomerResponse.
     * MapStruct maps it from customer.getUpdatedAt() — name matches, works automatically.
     */
    CustomerDetailResponse toDetailResponse(Customer customer);

    /**
     * Map a list of Customers to a list of CustomerResponses.
     *
     * MapStruct generates: list.stream().map(this::toResponse).collect(toList())
     * You get bulk mapping for free — no manual loop needed.
     * Used to map Page<Customer>.getContent() → List<CustomerResponse>.
     */
    List<CustomerResponse> toResponseList(List<Customer> customers);

    // ── Request → Entity (WRITE direction) ───────────────────────────────────

    /**
     * Map CustomerCreateRequest to a Customer entity.
     *
     * @Mapping(target = "id", ignore = true):
     * CustomerCreateRequest has no id field. MapStruct would try to set customer.id = null.
     * ignore = true: skip this field entirely. The DB generates the id via sequence.
     *
     * @Mapping(target = "active", constant = "true"):
     * CustomerCreateRequest has no 'active' field. New customers are always active.
     * constant = "true" injects a literal value "true" → mapped to boolean true.
     * This is the MapStruct way to set defaults.
     *
     * @Mapping(target = "projects", ignore = true):
     * CustomerCreateRequest has no projects. The @OneToMany collection starts empty.
     * Without ignore: MapStruct would set it to null → breaks the empty ArrayList() default.
     *
     * Fields createdAt, updatedAt: also ignored — set by @CreatedDate/@LastModifiedDate.
     */
    @Mapping(target = "id",         ignore = true)
    @Mapping(target = "active",     constant = "true")
    @Mapping(target = "projects",   ignore = true)
    @Mapping(target = "createdAt",  ignore = true)
    @Mapping(target = "updatedAt",  ignore = true)
    Customer toEntity(CustomerCreateRequest request);

    /**
     * Update an EXISTING Customer entity from an UpdateRequest.
     *
     * @MappingTarget Customer existing:
     * This is MapStruct's UPDATE pattern — unlike toEntity(), this method does NOT
     * create a new entity. It MODIFIES the existing one passed as @MappingTarget.
     *
     * WHY is this better than toEntity() for updates?
     * If we called toEntity(updateRequest) for an update:
     *   - We'd get a NEW Customer with id=null (we ignored id)
     *   - We'd lose all existing fields not in the request (active, projects)
     *   - We'd need to manually copy id and other fields back
     *   - Risk: accidentally overwriting fields we didn't mean to change
     *
     * With @MappingTarget:
     *   - MapStruct updates ONLY the fields present in the request
     *   - id, active, projects, createdAt stay untouched
     *   - The entity remains "managed" by Hibernate (dirty checking still works)
     *   - Safe: no risk of losing data
     *
     * Generated code looks like:
     *   existing.setName(request.name());
     *   existing.setEmail(request.email());
     *   existing.setPhone(request.phone());
     *   // id, active, projects, createdAt NOT touched
     *
     * Used in CustomerService.update():
     *   customerMapper.updateEntity(updateRequest, existingCustomer);
     *   // No return value needed — existingCustomer is modified in-place.
     *   // Dirty checking → Hibernate generates UPDATE automatically.
     */
    @Mapping(target = "id",         ignore = true)
    @Mapping(target = "active",     ignore = true)
    @Mapping(target = "projects",   ignore = true)
    @Mapping(target = "createdAt",  ignore = true)
    @Mapping(target = "updatedAt",  ignore = true)
    void updateEntity(CustomerUpdateRequest request, @MappingTarget Customer existing);
}