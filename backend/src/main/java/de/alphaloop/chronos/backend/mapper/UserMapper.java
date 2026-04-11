package de.alphaloop.chronos.backend.mapper;

import de.alphaloop.chronos.backend.domain.Role;
import de.alphaloop.chronos.backend.domain.User;
import de.alphaloop.chronos.backend.dto.response.UserResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;
import java.util.stream.Collectors;

/**
 * UserMapper — demonstrates two advanced MapStruct patterns:
 *
 * 1. EXPRESSION FOR COMPUTED FIELD (displayName):
 *    user.getDisplayName() is a business method on the entity.
 *    It's not a simple field getter — it has logic (firstName + lastName, or username).
 *    We use expression() to call it directly.
 *
 * 2. CUSTOM METHOD IN INTERFACE (roleToString):
 *    UserResponse.roles is List<String>, but User.roles is List<Role>.
 *    We need: for each Role, extract role.getRoletype().name() (the enum name as String).
 *
 *    MapStruct cannot auto-discover this conversion (Role → String is ambiguous —
 *    which field of Role becomes the String?).
 *
 *    Solution: define a DEFAULT METHOD in the mapper interface.
 *    MapStruct calls this default method for each element when mapping the list.
 *
 *    WHY a default method in the interface?
 *    Java interfaces can have default methods (since Java 8).
 *    MapStruct looks for a method with matching parameter/return types in the mapper.
 *    If it finds roleToString(Role): Role → String, it uses it automatically
 *    whenever it needs to convert a Role to a String in this mapper.
 *
 *    This keeps all role-related mapping logic in ONE place.
 *
 * 3. SECURITY: @Mapping(target = "passwordHash" ...) — intentionally ABSENT.
 *    UserResponse has NO password field. The mapper simply has no mapping for it.
 *    MapStruct ignores unmapped SOURCE fields by default (no compilation warning).
 *    The password hash never appears in any response — by design, enforced structurally.
 *
 *    Compare to the dangerous alternative:
 *    @JsonIgnore on User.passwordHash — easy to forget, leaks if someone removes the annotation.
 *    Our approach: UserResponse record has NO password field → physically impossible to leak.
 */
@Mapper(componentModel = "spring")
public interface UserMapper {

    /**
     * Map User to UserResponse.
     *
     * userName → username: field name mismatch (Java camelCase vs DTO snake-ish case).
     * The entity has "userName" (capital N), the DTO has "username" (lowercase).
     * @Mapping resolves this explicitly.
     *
     * displayName: computed from entity method getDisplayName().
     * expression = "java(user.getDisplayName())" calls the method directly.
     * Note: fix the entity first — User.java has GEtDisplayName() (typo) → should be getDisplayName().
     *
     * roles: List<Role> → List<String>.
     * MapStruct finds roleToString(Role) default method below → uses it for each element.
     * No explicit @Mapping needed — type matching is automatic.
     *
     * password_hash: NOT mapped → NOT in UserResponse → cannot leak. ✓
     */
    @Mapping(source = "userName",   target = "username")
    @Mapping(target = "displayName", expression = "java(user.getDisplayName())")
    UserResponse toResponse(User user);

    List<UserResponse> toResponseList(List<User> users);

    /**
     * Default method: Role → String (the enum name).
     *
     * MapStruct uses this automatically when it needs to convert Role → String.
     * role.getRoletype().name() returns: "ADMIN", "SALES", "LOGISTICS", "SERVICE".
     *
     * WHY .name() and not .toString()?
     * .name(): returns the exact enum constant name — guaranteed by Java spec.
     * .toString(): can be overridden to return anything (e.g. a display label).
     * .name() is safer for serialization — the value is always the declared constant name.
     *
     * WHY not use @ValueMapping (another MapStruct feature)?
     * @ValueMapping maps enum → enum (RoleType → String is not enum → enum).
     * For enum → String, a default method is the standard MapStruct approach.
     */
    default String roleToString(Role role) {
        if (role == null || role.getRoletype() == null) return null;
        return role.getRoletype().name();
    }
}