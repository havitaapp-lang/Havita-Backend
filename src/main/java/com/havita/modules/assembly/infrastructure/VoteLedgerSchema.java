package com.havita.modules.assembly.infrastructure;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Fase 2 / P4 — ¿la base tiene el esquema de participaciones y auditoria de votos (V104)?
 *
 * Las bases existentes (esquema de Hibernate, Flyway desactivado) NO lo tienen y la aplicacion debe seguir votando como
 * siempre en ellas, sin consultar tablas inexistentes. Las bases donde se aplico V104 (hoy, solo bases aisladas de
 * prueba) lo tienen, y alli V104 rechaza votos duplicados tambien en PostgreSQL: la aplicacion debe registrar cada voto
 * con su participacion. Por eso el modo se decide por el esquema real y no por una bandera: una bandera desactivada
 * con V104 aplicado dejaria votos sin participacion; una activada sin V104, consultas a tablas inexistentes.
 *
 * La deteccion usa solo {@code to_regclass} y el catalogo (nunca falla si las tablas no existen) y se hace una vez:
 * aplicar una migracion requiere reiniciar la aplicacion. Un esquema a medias (parcial) es un error de despliegue: no
 * se vota (fallo cerrado) en vez de votar sin las garantias.
 */
@Slf4j
@Component
public class VoteLedgerSchema {

    public enum State { ABSENT, COMPLETE, PARTIAL }

    private final JdbcTemplate jdbc;
    private volatile State state;

    public VoteLedgerSchema(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** true si V104 esta aplicada completa; false si no existe. Esquema parcial: excepcion (no se vota sin garantias). */
    public boolean active() {
        State s = state();
        if (s == State.PARTIAL) {
            throw new IllegalStateException("Esquema de votacion incompleto (V104 parcial): se rechaza el voto.");
        }
        return s == State.COMPLETE;
    }

    public State state() {
        State s = state;
        if (s == null) {
            synchronized (this) {
                if (state == null) {
                    state = detect();
                    log.info("Esquema de participaciones y auditoria de votos (V104): {}", state);
                }
                s = state;
            }
        }
        return s;
    }

    private volatile Boolean reissue;
    private volatile Boolean communityLimits;

    /**
     * Fase 2 / P5-A: ¿la base tiene V105 (cadena de nuevas emisiones de votos asistidos)? Sin V105, tras anular un voto
     * asistido no se permite una nueva emision (409 controlado), como en P4.
     */
    public boolean reissueSupported() {
        Boolean r = reissue;
        if (r == null) {
            r = active() && column("poll_participations", "supersedes_participation_id");
            reissue = r;
        }
        return r;
    }

    /** Fase 2 / P5-D: ¿la base tiene V106 (limite de representacion por comunidad)? Sin V106, solo el limite global. */
    public boolean communityLimitsSupported() {
        Boolean r = communityLimits;
        if (r == null) {
            r = Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT to_regclass('public.community_proxy_settings') IS NOT NULL", Boolean.class));
            communityLimits = r;
        }
        return r;
    }

    private boolean column(String table, String column) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM information_schema.columns WHERE "
                + "table_schema = 'public' AND table_name = ? AND column_name = ?)", Boolean.class, table, column));
    }

    private State detect() {
        Integer present = jdbc.queryForObject("SELECT (to_regclass('public.poll_participations') IS NOT NULL)::int "
                + "+ (to_regclass('public.assembly_vote_events') IS NOT NULL)::int "
                + "+ (EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'public' "
                + "AND table_name = 'poll_votes' AND column_name = 'participation_id'))::int", Integer.class);
        int n = present == null ? 0 : present;
        return n == 0 ? State.ABSENT : n == 3 ? State.COMPLETE : State.PARTIAL;
    }
}
