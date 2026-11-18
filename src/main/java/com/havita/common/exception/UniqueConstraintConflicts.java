package com.havita.common.exception;

import java.lang.reflect.Method;
import java.sql.SQLException;
import java.util.Map;
import java.util.Optional;

/**
 * Fase 2 / P0-C: traduce a conflicto de negocio (409) solo las violaciones de los indices unicos parciales conocidos que
 * protegen las reglas de residencia. Una violacion llega aqui cuando dos operaciones compatibles por separado se cruzan
 * (las comprobaciones previas de los servicios no bloquean) o cuando una ruta no hace comprobacion previa.
 *
 * La clasificacion exige las dos cosas, ambas estructuradas (no se analiza el texto del mensaje SQL):
 * <ul>
 *   <li>SQLSTATE {@code 23505} (unique_violation) en la {@link SQLException} de la cadena de causas;</li>
 *   <li>el nombre de la restriccion que PostgreSQL envia en el campo {@code constraint} del error (protocolo, campo 'n'),
 *       leido del driver por reflexion porque el driver es solo de ejecucion.</li>
 * </ul>
 * Cualquier otra cosa (otra restriccion unica, clave foranea, CHECK, NOT NULL, conexion, otro motor) no se clasifica y
 * sigue el tratamiento de error inesperado. Los mensajes no incluyen SQL, tablas, indices ni datos de terceros.
 */
public final class UniqueConstraintConflicts {

    public static final String UNIQUE_VIOLATION = "23505";

    public record Conflict(String code, String message) {}

    private static final Conflict SECOND_ACTIVE_TENANT = new Conflict("SECOND_ACTIVE_TENANT",
            "Esta unidad ya tiene un arrendatario activo. Debes retirar al arrendatario actual antes de registrar otro.");
    private static final Conflict ALREADY_ACTIVE_IN_ANOTHER_PROPERTY = new Conflict("ALREADY_ACTIVE_IN_ANOTHER_PROPERTY",
            "Esta persona ya tiene una residencia activa como residente o arrendatario. "
                    + "Un residente o arrendatario solo puede tener un apartamento activo a la vez.");
    private static final Conflict PENDING_TENANT_INVITATION = new Conflict("PENDING_TENANT_INVITATION",
            "Ya existe una invitación pendiente para un arrendatario en esta unidad. Debes cancelarla antes de generar una nueva.");

    private static final Conflict PROXY_ALREADY_EXISTS = new Conflict("PROXY_ALREADY_EXISTS",
            "La unidad ya tiene un poder en revisión o aprobado para esta asamblea.");

    private static final Conflict PROPERTY_ALREADY_VOTED = new Conflict("PROPERTY_ALREADY_VOTED",
            "El inmueble ya ha ejercido su voto en esta votación.");

    /** Nombres reales de los indices (V100 = base local). Los codigos son los que ya usan los servicios. */
    static final Map<String, Conflict> KNOWN = Map.of(
            "uq_active_arrendatario_per_property", SECOND_ACTIVE_TENANT,
            "uq_active_non_owner_residence_per_user", ALREADY_ACTIVE_IN_ANOTHER_PROPERTY,
            "uq_active_non_owner_residence_per_email", ALREADY_ACTIVE_IN_ANOTHER_PROPERTY,
            "uq_pending_arrendatario_invitation", PENDING_TENANT_INVITATION,
            // Fase 2 / P1 (V102): un solo poder vigente por asamblea y unidad.
            "uq_assembly_proxy_live_per_unit", PROXY_ALREADY_EXISTS,
            // Fase 2 / P4 (V104, V-1): una participacion vigente por pregunta y unidad (indice y trigger de poll_votes).
            "uq_poll_participation_valid", PROPERTY_ALREADY_VOTED,
            "poll_votes_one_participation_per_unit", PROPERTY_ALREADY_VOTED,
            // Fase 2 / P5-A (V105): una participacion anulada se sustituye una sola vez (dos nuevas emisiones a la vez).
            "uq_poll_participations_supersedes", PROPERTY_ALREADY_VOTED);

    private UniqueConstraintConflicts() {}

    /** Conflicto de negocio conocido, o vacio si la excepcion no es una violacion de uno de esos indices. */
    public static Optional<Conflict> classify(Throwable error) {
        for (Throwable t = error, prev = null; t != null && t != prev; prev = t, t = t.getCause()) {
            if (t instanceof SQLException sql) {
                for (SQLException s = sql; s != null; s = s.getNextException()) {
                    if (UNIQUE_VIOLATION.equals(s.getSQLState())) {
                        String constraint = serverConstraint(s);
                        if (constraint != null && KNOWN.containsKey(constraint)) {
                            return Optional.of(KNOWN.get(constraint));
                        }
                    }
                }
            }
        }
        return Optional.empty();
    }

    /** Campo {@code constraint} del error de PostgreSQL (PSQLException#getServerErrorMessage().getConstraint()). */
    static String serverConstraint(SQLException e) {
        try {
            Method serverMessage = e.getClass().getMethod("getServerErrorMessage");
            Object message = serverMessage.invoke(e);
            if (message == null) {
                return null;
            }
            Object constraint = message.getClass().getMethod("getConstraint").invoke(message);
            return constraint instanceof String s ? s : null;
        } catch (ReflectiveOperationException | RuntimeException notPostgres) {
            return null;
        }
    }
}
