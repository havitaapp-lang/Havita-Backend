package com.havita.modules.sync.domain;

import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Destinatarios de un {@link SyncEvent} dentro de su comunidad. La entrega nunca sale del tenant del evento.
 *
 * <ul>
 *   <li>{@code TENANT}: todas las conexiones de la comunidad (comportamiento historico, por defecto).</li>
 *   <li>{@code STAFF}: solo roles de gestion (SUPER_ADMIN, PLATFORM_ADMIN, COMMUNITY_ADMIN, SECURITY, STAFF).</li>
 *   <li>{@code UNIT}: roles de gestion y residentes con relacion ACTIVE en {@code propertyId}; si {@code userIds}
 *       no esta vacio, de esos residentes solo los usuarios indicados.</li>
 *   <li>{@code RESIDENTS}: solo conexiones con rol RESIDENT (version de un evento para residentes).</li>
 *   <li>{@code USERS}: solo los usuarios indicados, con cualquier rol y aunque ya no tengan relacion con la unidad
 *       (por ejemplo, el aviso de revocacion que el afectado necesita para cerrar sesion).</li>
 * </ul>
 */
public record SyncAudience(Scope scope, UUID propertyId, Set<UUID> userIds) {

    public enum Scope { TENANT, STAFF, UNIT, RESIDENTS, USERS }

    public static final SyncAudience TENANT = new SyncAudience(Scope.TENANT, null, Set.of());

    public SyncAudience {
        Objects.requireNonNull(scope, "scope");
        userIds = userIds == null ? Set.of() : Set.copyOf(userIds);
    }

    public static SyncAudience staff() {
        return new SyncAudience(Scope.STAFF, null, Set.of());
    }

    public static SyncAudience residents() {
        return new SyncAudience(Scope.RESIDENTS, null, Set.of());
    }

    /** Solo los usuarios indicados (los nulos se ignoran); sin usuarios, nadie lo recibe. */
    public static SyncAudience users(Collection<UUID> userIds) {
        Set<UUID> ids = userIds.stream().filter(Objects::nonNull).collect(Collectors.toUnmodifiableSet());
        return new SyncAudience(Scope.USERS, null, ids);
    }

    /** Gestion y residentes activos de la unidad. Sin unidad, equivale a {@link #staff()}. */
    public static SyncAudience unit(UUID propertyId) {
        return new SyncAudience(Scope.UNIT, propertyId, Set.of());
    }

    /** Gestion y, de los residentes activos de la unidad, solo los usuarios indicados (los nulos se ignoran). */
    public static SyncAudience unitUsers(UUID propertyId, Collection<UUID> userIds) {
        Set<UUID> ids = userIds.stream().filter(Objects::nonNull).collect(Collectors.toUnmodifiableSet());
        return ids.isEmpty() ? staff() : new SyncAudience(Scope.UNIT, propertyId, ids);
    }
}
