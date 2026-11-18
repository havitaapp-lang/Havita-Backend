-- Fase 2 / P5-C/D — Limite de representacion por comunidad y auditoria de integridad de documentos de poderes
-- (PREPARADO, NO ACTIVADO).
--
-- Migracion incremental posterior a V105. NO modifica V100-V105 ni ningun dato existente: crea dos tablas nuevas y
-- sustituye (DROP + ADD) el CHECK de acciones de assembly_proxy_events conservando TODAS las anteriores (V102 + V103).
-- Flyway sigue desactivado. Sin entidades JPA (acceso JDBC).
--
-- Limite por comunidad (P5-D): sin fila -> se aplica el valor global (havita.assembly-proxies.max-units-per-proxy, que
-- por defecto no existe = sin limite); con fila -> max_units_per_proxy de la comunidad (NULL = sin limite en esa
-- comunidad). Solo enteros positivos: no se inventan limites juridicos. El limite se evalua al APROBAR y al USAR un
-- poder; no se aplica retroactivamente a poderes ya utilizados ni a votos.

CREATE TABLE public.community_proxy_settings (
    tenant_id            uuid        NOT NULL,
    max_units_per_proxy  integer,
    updated_by_user_id   uuid        NOT NULL,
    updated_at           timestamptz NOT NULL,
    version              bigint      NOT NULL DEFAULT 0,
    CONSTRAINT community_proxy_settings_pkey PRIMARY KEY (tenant_id),
    CONSTRAINT fk_community_proxy_settings_tenant FOREIGN KEY (tenant_id) REFERENCES public.tenants (id),
    CONSTRAINT fk_community_proxy_settings_updated_by FOREIGN KEY (updated_by_user_id) REFERENCES public.users (id),
    CONSTRAINT community_proxy_settings_limit_check CHECK (max_units_per_proxy IS NULL OR max_units_per_proxy > 0)
);

-- Historial de cambios del limite (solo insercion): quien, cuando, valor anterior y nuevo.
CREATE TABLE public.community_proxy_settings_events (
    id                       uuid        NOT NULL,
    tenant_id                uuid        NOT NULL,
    actor_user_id            uuid        NOT NULL,
    previous_configured      boolean     NOT NULL,
    previous_max_units       integer,
    new_max_units            integer,
    occurred_at              timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT community_proxy_settings_events_pkey PRIMARY KEY (id),
    CONSTRAINT fk_community_proxy_settings_events_tenant FOREIGN KEY (tenant_id) REFERENCES public.tenants (id),
    CONSTRAINT fk_community_proxy_settings_events_actor FOREIGN KEY (actor_user_id) REFERENCES public.users (id)
);

CREATE INDEX ix_community_proxy_settings_events_tenant ON public.community_proxy_settings_events (tenant_id, occurred_at);

CREATE FUNCTION public.community_proxy_settings_events_append_only() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'community_proxy_settings_events: historial de solo insercion'
        USING ERRCODE = '23514', CONSTRAINT = 'community_proxy_settings_events_append_only';
END;
$$;

CREATE TRIGGER trg_community_proxy_settings_events_append_only
    BEFORE UPDATE OR DELETE ON public.community_proxy_settings_events
    FOR EACH ROW EXECUTE FUNCTION public.community_proxy_settings_events_append_only();

CREATE TRIGGER trg_community_proxy_settings_events_no_truncate
    BEFORE TRUNCATE ON public.community_proxy_settings_events
    FOR EACH STATEMENT EXECUTE FUNCTION public.havita_reject_truncate();

-- Auditoria de documentos de poderes (P5-C): fallo de integridad (SHA-256 distinto del registrado) y documento
-- ausente en el almacenamiento. Se conservan todas las acciones de V102 y V103.
ALTER TABLE public.assembly_proxy_events DROP CONSTRAINT assembly_proxy_events_action_check;
ALTER TABLE public.assembly_proxy_events ADD CONSTRAINT assembly_proxy_events_action_check CHECK (action IN (
    'PROXY_REQUESTED', 'PROXY_DOCUMENT_REGISTERED', 'PROXY_DOCUMENT_REPLACED', 'PROXY_APPROVED', 'PROXY_REJECTED',
    'PROXY_REVOKED', 'PROXY_EXPIRED', 'PROXY_TRANSITION_DENIED', 'PROXY_DOCUMENT_ACCESSED',
    'PROXY_USED', 'PROXY_ATTENDANCE_REGISTERED', 'PROXY_IDENTITY_VERIFIED', 'PROXY_ATTENDANCE_DENIED',
    'DELEGATION_BLOCKED_BY_PROXY', 'ATTENDANCE_BLOCKED_BY_PROXY',
    'PROXY_DOCUMENT_INTEGRITY_FAILED', 'PROXY_DOCUMENT_MISSING'));
