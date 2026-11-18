package com.havita.security;

import com.havita.common.exception.BusinessRuleConflictException;
import com.havita.common.exception.InvalidRequestException;
import com.havita.modules.identity.infrastructure.UserRepository;
import com.havita.modules.property.domain.Property;
import com.havita.modules.property.domain.Structure;
import com.havita.modules.resident.domain.Resident;
import com.havita.modules.resident.domain.ResidentStatus;
import com.havita.modules.resident.infrastructure.ResidentRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Fase 2 / E1: resolvedor UNICO de la unidad con la que opera un residente. La unidad seleccionada en el cliente
 * ({@value #HEADER}) solo ELIGE entre las unidades que PostgreSQL dice que son suyas hoy; nunca las amplia ni autoriza
 * por si misma (cada servicio sigue validando el recurso concreto).
 *
 * <ul>
 *   <li>Unidades: relaciones ACTIVE del usuario en la comunidad de la peticion, una entrada por unidad (con todos sus
 *       tipos de relacion), en orden natural estable: estructura, unidad, id. Sin estructura, al final.</li>
 *   <li>Cabecera valida y entre sus unidades: esa unidad.</li>
 *   <li>Cabecera de una unidad ajena, de otra comunidad, revocada o inexistente: 409 {@code UNIT_NOT_AVAILABLE}, igual
 *       para todas (no revela si existe ni de quien es). Nunca se sustituye por otra unidad.</li>
 *   <li>Cabecera mal formada: 400 {@code INVALID_UNIT_ID}.</li>
 *   <li>Sin cabecera: con una unidad, esa; con varias, 409 {@code UNIT_SELECTION_REQUIRED}.</li>
 *   <li>Sin ninguna unidad ACTIVE: 409 {@code ACCESS_REVOKED} si tuvo alguna, {@code NO_PROPERTY_ASSIGNED} si nunca.
 *       Membresia revocada o cuenta inactiva no llegan aqui: el filtro JWT deja la peticion anonima (401).</li>
 * </ul>
 * Sin cache: se consulta en cada peticion, asi que una revocacion surte efecto en la siguiente.
 */
@Component
@RequiredArgsConstructor
public class ResidentUnitResolver {

    public static final String HEADER = "X-Property-Id";

    private final ResidentRepository residentRepository;
    private final UserRepository userRepository;

    /** Una unidad disponible para el usuario (contrato de {@code GET /resident/me/properties}). */
    public record UnitOption(
            UUID propertyId,
            UUID tenantId,
            String number,
            String name,
            String propertyType,
            String propertyStatus,
            String level,
            UUID structureId,
            String structureName,
            String structureType,
            List<String> relations,
            boolean mainContact,
            String label,
            String legacyPath,
            UUID residentId) {

        /** Relacion principal (Propietario > Arrendatario > Residente): el antiguo campo {@code type}. */
        public String primaryRelation() {
            return relations.isEmpty() ? null : relations.get(0);
        }
    }

    /** Unidad resuelta para una operacion: la unidad y la relacion del usuario con ella. */
    public record ResolvedUnit(UUID propertyId, UUID residentId, UnitOption unit) {}

    // ── API ───────────────────────────────────────────────────────────────────

    /** Unidad de la peticion actual segun {@value #HEADER} (ver reglas en la clase). */
    @Transactional(readOnly = true)
    public ResolvedUnit resolveCurrent() {
        return resolveCurrent(parseUnitId(currentHeader()));
    }

    /**
     * Como {@link #resolveCurrent()} pero con una unidad explicita del contrato del endpoint (p. ej. el cuerpo); si es
     * null se usa la cabecera. La unidad explicita sigue las mismas reglas: nunca se sustituye por otra.
     */
    @Transactional(readOnly = true)
    public ResolvedUnit resolveCurrent(UUID explicitUnitId) {
        UUID userId = currentUserId();
        UUID tenantId = TenantContextHolder.getTenantId();
        if (userId == null || tenantId == null) {
            throw new BusinessRuleConflictException("NO_TENANT", "No hay contexto de copropiedad.");
        }
        return resolve(userId, emailOf(userId), tenantId, explicitUnitId);
    }

    @Transactional(readOnly = true)
    public ResolvedUnit resolve(UUID userId, String email, UUID tenantId, UUID requestedUnitId) {
        List<UnitOption> units = activeUnits(userId, email, tenantId);
        if (units.isEmpty()) {
            throw noActiveUnit(userId, email, tenantId);
        }
        if (requestedUnitId != null) {
            return units.stream()
                    .filter(u -> u.propertyId().equals(requestedUnitId))
                    .findFirst()
                    .map(u -> new ResolvedUnit(u.propertyId(), u.residentId(), u))
                    .orElseThrow(() -> new BusinessRuleConflictException("UNIT_NOT_AVAILABLE",
                            "La unidad seleccionada no está disponible para tu cuenta. Selecciona otra de tus unidades."));
        }
        if (units.size() > 1) {
            throw new BusinessRuleConflictException("UNIT_SELECTION_REQUIRED",
                    "Tienes varias unidades en esta comunidad. Selecciona la unidad con la que quieres continuar.");
        }
        UnitOption only = units.get(0);
        return new ResolvedUnit(only.propertyId(), only.residentId(), only);
    }

    /** Unidades ACTIVE del usuario en la comunidad actual, en el orden estable del selector. */
    @Transactional(readOnly = true)
    public List<UnitOption> currentUnits() {
        UUID userId = currentUserId();
        UUID tenantId = TenantContextHolder.getTenantId();
        if (userId == null || tenantId == null) {
            throw new BusinessRuleConflictException("NO_TENANT", "No hay contexto de copropiedad.");
        }
        String email = emailOf(userId);
        List<UnitOption> units = activeUnits(userId, email, tenantId);
        if (units.isEmpty() && residentRepository.existsUnitRelation(userId, email, tenantId, ResidentStatus.INACTIVE)) {
            throw new BusinessRuleConflictException("ACCESS_REVOKED", "Tu acceso a esta copropiedad ha sido revocado.");
        }
        return units;
    }

    /** Unidades ACTIVE del usuario en la comunidad, deduplicadas y ordenadas. Una consulta, sin N+1. */
    @Transactional(readOnly = true)
    public List<UnitOption> activeUnits(UUID userId, String email, UUID tenantId) {
        if (userId == null || tenantId == null) {
            return List.of();
        }
        Map<UUID, List<Resident>> byUnit = new LinkedHashMap<>();
        for (Resident r : residentRepository.findUnitRelations(userId, email, tenantId, ResidentStatus.ACTIVE)) {
            if (r.getProperty() != null) {
                byUnit.computeIfAbsent(r.getProperty().getId(), k -> new ArrayList<>()).add(r);
            }
        }
        List<UnitOption> units = new ArrayList<>();
        byUnit.forEach((propertyId, relations) -> units.add(toOption(userId, relations)));
        units.sort(UNIT_ORDER);
        return List.copyOf(units);
    }

    /** true si el usuario tiene al menos una unidad ACTIVE en la comunidad (misma regla que {@link #activeUnits}). */
    @Transactional(readOnly = true)
    public boolean hasActiveUnit(UUID userId, String email, UUID tenantId) {
        return userId != null && tenantId != null
                && residentRepository.existsUnitRelation(userId, email, tenantId, ResidentStatus.ACTIVE);
    }

    /** true si tuvo relaciones con unidades de la comunidad pero ninguna sigue ACTIVE. */
    @Transactional(readOnly = true)
    public boolean hasOnlyInactiveUnits(UUID userId, String email, UUID tenantId) {
        return !hasActiveUnit(userId, email, tenantId)
                && residentRepository.existsUnitRelation(userId, email, tenantId, ResidentStatus.INACTIVE);
    }

    /** Valor de {@value #HEADER} o de un campo de unidad: null si falta o esta vacio; 400 si no es un UUID. */
    public static UUID parseUnitId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException ex) {
            throw new InvalidRequestException("INVALID_UNIT_ID", "El identificador de unidad no es válido.");
        }
    }

    // ── interno ───────────────────────────────────────────────────────────────

    private RuntimeException noActiveUnit(UUID userId, String email, UUID tenantId) {
        if (residentRepository.existsUnitRelation(userId, email, tenantId, ResidentStatus.INACTIVE)) {
            return new BusinessRuleConflictException("ACCESS_REVOKED",
                    "Tu acceso a esta copropiedad ha sido revocado. Contacta al propietario o administrador.");
        }
        return new BusinessRuleConflictException("NO_PROPERTY_ASSIGNED",
                "Tu usuario no tiene ninguna vivienda asignada. Contacta al administrador.");
    }

    private static UnitOption toOption(UUID userId, List<Resident> relations) {
        List<Resident> ordered = new ArrayList<>(relations);
        // Relacion principal: la vinculada a la cuenta antes que la de solo correo; despues por tipo e id.
        ordered.sort(Comparator
                .comparing((Resident r) -> r.getUser() != null && userId.equals(r.getUser().getId()) ? 0 : 1)
                .thenComparingInt(r -> relationRank(r.getType()))
                .thenComparing(r -> r.getId().toString()));
        Resident primary = ordered.get(0);
        Property p = primary.getProperty();
        Structure s = p.getStructure();
        List<String> types = relations.stream().map(Resident::getType).filter(Objects::nonNull).distinct()
                .sorted(Comparator.comparingInt(ResidentUnitResolver::relationRank).thenComparing(Comparator.naturalOrder()))
                .toList();
        String unitName = p.getName() != null && !p.getName().isBlank() ? p.getName() : p.getNumber();
        return new UnitOption(
                p.getId(),
                p.getTenantId(),
                p.getNumber(),
                p.getName(),
                p.getType(),
                p.getStatus() != null ? p.getStatus().name() : null,
                p.getLevel(),
                s != null ? s.getId() : null,
                s != null ? s.getName() : null,
                s != null ? s.getType() : null,
                types,
                relations.stream().anyMatch(r -> Boolean.TRUE.equals(r.getIsMainContact())),
                s != null ? s.getName() + " · " + unitName : unitName,
                legacyPath(p, s),
                primary.getId());
    }

    /** El antiguo campo {@code path} de /resident/me/properties, sin cambios ("Torre 1-Piso 5-Apto 2"). */
    private static String legacyPath(Property p, Structure s) {
        String str = s != null ? s.getName() + "-" : "";
        if (p.getLevel() != null) {
            String lvl = p.getLevel().trim();
            String lower = lvl.toLowerCase();
            str += (lower.startsWith("piso") || lower.startsWith("nivel") || lower.startsWith("sótano") || lower.startsWith("etapa"))
                    ? lvl + "-" : "Piso " + lvl + "-";
        }
        return str + (p.getName() != null ? p.getName() : p.getNumber());
    }

    private static int relationRank(String type) {
        if (type == null) return 9;
        return switch (type.trim().toLowerCase()) {
            case "propietario" -> 0;
            case "arrendatario" -> 1;
            case "residente" -> 2;
            default -> 3;
        };
    }

    /** Orden del selector: estructura (natural, sin estructura al final), unidad (natural), id. */
    static final Comparator<UnitOption> UNIT_ORDER = Comparator
            .comparing((UnitOption u) -> u.structureName() == null ? 1 : 0)
            .thenComparing(UnitOption::structureName, Comparator.nullsLast(ResidentUnitResolver::naturalCompare))
            .thenComparing(u -> u.name() != null && !u.name().isBlank() ? u.name() : u.number(),
                    Comparator.nullsLast(ResidentUnitResolver::naturalCompare))
            .thenComparing(UnitOption::number, Comparator.nullsLast(ResidentUnitResolver::naturalCompare))
            .thenComparing(u -> u.propertyId().toString());

    /** Comparacion "natural": los tramos de digitos se comparan como numeros ("Torre 2" antes que "Torre 10"). */
    static int naturalCompare(String x, String y) {
        int i = 0, j = 0;
        String a = x.toLowerCase(), b = y.toLowerCase();
        while (i < a.length() && j < b.length()) {
            char ca = a.charAt(i), cb = b.charAt(j);
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int si = i, sj = j;
                while (i < a.length() && Character.isDigit(a.charAt(i))) i++;
                while (j < b.length() && Character.isDigit(b.charAt(j))) j++;
                String na = a.substring(si, i).replaceFirst("^0+(?=.)", "");
                String nb = b.substring(sj, j).replaceFirst("^0+(?=.)", "");
                int c = na.length() != nb.length() ? Integer.compare(na.length(), nb.length()) : na.compareTo(nb);
                if (c != 0) return c;
            } else {
                if (ca != cb) return Character.compare(ca, cb);
                i++;
                j++;
            }
        }
        return Integer.compare(a.length() - i, b.length() - j);
    }

    private String emailOf(UUID userId) {
        return userRepository.findById(userId).map(u -> u.getEmail()).orElse(null);
    }

    private static UUID currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof String principal)) {
            return null;
        }
        try {
            return UUID.fromString(principal);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static String currentHeader() {
        return Optional.ofNullable(RequestContextHolder.getRequestAttributes())
                .filter(ServletRequestAttributes.class::isInstance)
                .map(a -> ((ServletRequestAttributes) a).getRequest())
                .map((HttpServletRequest r) -> r.getHeader(HEADER))
                .orElse(null);
    }
}
