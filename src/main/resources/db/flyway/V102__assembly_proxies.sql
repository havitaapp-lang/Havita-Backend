-- Fase 2 / P1 — Modelo de poderes verificables para asambleas (PREPARADO, NO ACTIVADO).
--
-- Migracion incremental posterior a V101. NO modifica V100/V101. Flyway sigue desactivado en todos los perfiles: este
-- archivo solo se aplica en bases aisladas (Testcontainers) o en una adopcion futura aprobada (baseline 100 + migrate).
-- Las tablas NO tienen entidad JPA a proposito: con ddl-auto=update, Hibernate las crearia en la base real sin los
-- indices parciales, CHECK ni triggers de este archivo. La aplicacion accede a ellas por JDBC y solo si
-- havita.assembly-proxies.enabled=true (por defecto false).
--
-- Reglas: un poder por asamblea y unidad (P1-R3), con documento obligatorio (P1-R4), otorgado por una relacion de
-- propietario de esa misma unidad y comunidad (P1-R1), revisado por la administracion (P1-R5). Un solo poder vigente
-- (PENDING_REVIEW, APPROVED o USED) por asamblea y unidad (P1-R2). Sin relaciones cruzadas entre comunidades.

-- 1. Claves compuestas para las FK multi-tenant (id ya es PK; estas restricciones son trivialmente ciertas y solo
--    permiten que las FK exijan que asamblea, unidad y otorgante sean de la MISMA comunidad que el poder).
ALTER TABLE public.assemblies ADD CONSTRAINT uq_assemblies_id_tenant UNIQUE (id, tenant_id);
ALTER TABLE public.properties ADD CONSTRAINT uq_properties_id_tenant UNIQUE (id, tenant_id);
ALTER TABLE public.residents ADD CONSTRAINT uq_residents_id_tenant_property UNIQUE (id, tenant_id, property_id);

-- 2. Poderes.
CREATE TABLE public.assembly_proxies (
    id                              uuid         NOT NULL,
    tenant_id                       uuid         NOT NULL,
    assembly_id                     uuid         NOT NULL,
    property_id                     uuid         NOT NULL,
    -- Otorgante: la relacion de propietario (censo) de esta unidad y, si la tiene, su cuenta.
    grantor_resident_id             uuid         NOT NULL,
    grantor_user_id                 uuid,
    -- Apoderado: cuenta de Havita opcional; la identificacion presencial es obligatoria siempre.
    proxy_user_id                   uuid,
    proxy_full_name                 varchar(150) NOT NULL,
    proxy_document_type             varchar(20)  NOT NULL,
    proxy_document_number           varchar(50)  NOT NULL,
    status                          varchar(20)  NOT NULL,
    -- Documento de soporte: referencia opaca y metadatos minimos (sin nombre original del archivo).
    document_key                    varchar(500) NOT NULL,
    document_content_type           varchar(100) NOT NULL,
    document_size_bytes             bigint       NOT NULL,
    document_sha256                 char(64)     NOT NULL,
    document_registered_at          timestamptz  NOT NULL,
    document_registered_by_user_id  uuid         NOT NULL,
    -- Registro: quien lo creo y por que canal (el propietario o la administracion en su nombre).
    created_by_user_id              uuid         NOT NULL,
    created_channel                 varchar(10)  NOT NULL,
    -- Revision administrativa.
    reviewed_by_user_id             uuid,
    reviewed_at                     timestamptz,
    review_decision                 varchar(10),
    rejection_reason                varchar(500),
    -- Revocacion.
    revoked_by_user_id              uuid,
    revoked_at                      timestamptz,
    revocation_reason               varchar(500),
    -- Uso (lo fijara la asistencia en una etapa posterior) y vencimiento.
    used_at                         timestamptz,
    expired_at                      timestamptz,
    created_at                      timestamptz  NOT NULL,
    updated_at                      timestamptz  NOT NULL,
    version                         bigint       NOT NULL DEFAULT 0,

    CONSTRAINT assembly_proxies_pkey PRIMARY KEY (id),
    CONSTRAINT fk_assembly_proxies_tenant FOREIGN KEY (tenant_id) REFERENCES public.tenants (id),
    CONSTRAINT fk_assembly_proxies_assembly FOREIGN KEY (assembly_id, tenant_id) REFERENCES public.assemblies (id, tenant_id),
    CONSTRAINT fk_assembly_proxies_property FOREIGN KEY (property_id, tenant_id) REFERENCES public.properties (id, tenant_id),
    CONSTRAINT fk_assembly_proxies_grantor_resident FOREIGN KEY (grantor_resident_id, tenant_id, property_id)
        REFERENCES public.residents (id, tenant_id, property_id),
    CONSTRAINT fk_assembly_proxies_grantor_user FOREIGN KEY (grantor_user_id) REFERENCES public.users (id),
    CONSTRAINT fk_assembly_proxies_proxy_user FOREIGN KEY (proxy_user_id) REFERENCES public.users (id),
    CONSTRAINT fk_assembly_proxies_document_registered_by FOREIGN KEY (document_registered_by_user_id) REFERENCES public.users (id),
    CONSTRAINT fk_assembly_proxies_created_by FOREIGN KEY (created_by_user_id) REFERENCES public.users (id),
    CONSTRAINT fk_assembly_proxies_reviewed_by FOREIGN KEY (reviewed_by_user_id) REFERENCES public.users (id),
    CONSTRAINT fk_assembly_proxies_revoked_by FOREIGN KEY (revoked_by_user_id) REFERENCES public.users (id),

    CONSTRAINT uq_assembly_proxies_document_key UNIQUE (document_key),

    CONSTRAINT assembly_proxies_status_check CHECK (status IN ('PENDING_REVIEW', 'APPROVED', 'REJECTED', 'REVOKED', 'USED', 'EXPIRED')),
    CONSTRAINT assembly_proxies_channel_check CHECK (created_channel IN ('OWNER', 'ADMIN')),
    CONSTRAINT assembly_proxies_review_decision_check CHECK (review_decision IS NULL OR review_decision IN ('APPROVED', 'REJECTED')),
    CONSTRAINT assembly_proxies_document_type_check CHECK (proxy_document_type IN ('CC', 'CE', 'PASAPORTE', 'PEP')),
    CONSTRAINT assembly_proxies_name_check CHECK (btrim(proxy_full_name) <> ''),
    CONSTRAINT assembly_proxies_document_number_check CHECK (btrim(proxy_document_number) <> ''),
    CONSTRAINT assembly_proxies_content_type_check CHECK (document_content_type IN ('application/pdf', 'image/png', 'image/jpeg')),
    CONSTRAINT assembly_proxies_size_check CHECK (document_size_bytes > 0),
    CONSTRAINT assembly_proxies_sha256_check CHECK (document_sha256 ~ '^[0-9a-f]{64}$'),
    -- El apoderado con cuenta no es el propio otorgante.
    CONSTRAINT assembly_proxies_not_self_check CHECK (proxy_user_id IS NULL OR grantor_user_id IS NULL OR proxy_user_id <> grantor_user_id),
    -- Coherencia de cada estado con sus datos (no se puede aprobar sin revision; rechazar exige motivo; etc.).
    CONSTRAINT assembly_proxies_state_data_check CHECK (
        CASE status
            WHEN 'PENDING_REVIEW' THEN reviewed_at IS NULL AND review_decision IS NULL AND revoked_at IS NULL
                                       AND used_at IS NULL AND expired_at IS NULL
            WHEN 'APPROVED'       THEN review_decision = 'APPROVED' AND reviewed_at IS NOT NULL AND reviewed_by_user_id IS NOT NULL
                                       AND revoked_at IS NULL AND used_at IS NULL AND expired_at IS NULL
            WHEN 'REJECTED'       THEN review_decision = 'REJECTED' AND reviewed_at IS NOT NULL AND reviewed_by_user_id IS NOT NULL
                                       AND rejection_reason IS NOT NULL AND btrim(rejection_reason) <> ''
            WHEN 'REVOKED'        THEN revoked_at IS NOT NULL AND revoked_by_user_id IS NOT NULL AND used_at IS NULL
            WHEN 'USED'           THEN review_decision = 'APPROVED' AND used_at IS NOT NULL AND revoked_at IS NULL
            WHEN 'EXPIRED'        THEN expired_at IS NOT NULL AND used_at IS NULL
        END)
);

-- Un solo poder vigente (en revision, aprobado o utilizado) por asamblea y unidad: dos copropietarios o dos
-- administradores no pueden dejar dos representaciones incompatibles, ni siquiera en carrera.
CREATE UNIQUE INDEX uq_assembly_proxy_live_per_unit ON public.assembly_proxies (assembly_id, property_id)
    WHERE status IN ('PENDING_REVIEW', 'APPROVED', 'USED');

CREATE INDEX ix_assembly_proxies_tenant_assembly_status ON public.assembly_proxies (tenant_id, assembly_id, status);
CREATE INDEX ix_assembly_proxies_property ON public.assembly_proxies (property_id);
CREATE INDEX ix_assembly_proxies_proxy_user ON public.assembly_proxies (assembly_id, proxy_user_id) WHERE proxy_user_id IS NOT NULL;
CREATE INDEX ix_assembly_proxies_proxy_document ON public.assembly_proxies (assembly_id, proxy_document_type, upper(proxy_document_number));

-- Transiciones de estado e inmutabilidad, verificadas tambien en PostgreSQL (no solo en Java).
CREATE FUNCTION public.assembly_proxies_guard() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'assembly_proxies: los poderes no se eliminan (historial)'
            USING ERRCODE = '23514', CONSTRAINT = 'assembly_proxies_no_delete';
    END IF;

    -- Columnas que nunca cambian.
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
       OR NEW.assembly_id IS DISTINCT FROM OLD.assembly_id OR NEW.property_id IS DISTINCT FROM OLD.property_id
       OR NEW.grantor_resident_id IS DISTINCT FROM OLD.grantor_resident_id OR NEW.grantor_user_id IS DISTINCT FROM OLD.grantor_user_id
       OR NEW.created_by_user_id IS DISTINCT FROM OLD.created_by_user_id OR NEW.created_channel IS DISTINCT FROM OLD.created_channel
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'assembly_proxies: columnas inmutables'
            USING ERRCODE = '23514', CONSTRAINT = 'assembly_proxies_immutable';
    END IF;

    -- Identidad del apoderado y documento: solo mientras el poder sigue en revision (y sin cambiar de estado).
    IF (NEW.proxy_user_id IS DISTINCT FROM OLD.proxy_user_id OR NEW.proxy_full_name IS DISTINCT FROM OLD.proxy_full_name
        OR NEW.proxy_document_type IS DISTINCT FROM OLD.proxy_document_type
        OR NEW.proxy_document_number IS DISTINCT FROM OLD.proxy_document_number
        OR NEW.document_key IS DISTINCT FROM OLD.document_key OR NEW.document_sha256 IS DISTINCT FROM OLD.document_sha256
        OR NEW.document_content_type IS DISTINCT FROM OLD.document_content_type
        OR NEW.document_size_bytes IS DISTINCT FROM OLD.document_size_bytes)
       AND NOT (OLD.status = 'PENDING_REVIEW' AND NEW.status = 'PENDING_REVIEW') THEN
        RAISE EXCEPTION 'assembly_proxies: identidad o documento solo modificables en revision'
            USING ERRCODE = '23514', CONSTRAINT = 'assembly_proxies_identity_locked';
    END IF;

    -- Datos de revision, revocacion, uso y vencimiento: una vez fijados no se reescriben.
    IF (OLD.reviewed_at IS NOT NULL AND (NEW.reviewed_at IS DISTINCT FROM OLD.reviewed_at
            OR NEW.reviewed_by_user_id IS DISTINCT FROM OLD.reviewed_by_user_id OR NEW.review_decision IS DISTINCT FROM OLD.review_decision
            OR NEW.rejection_reason IS DISTINCT FROM OLD.rejection_reason))
       OR (OLD.revoked_at IS NOT NULL AND (NEW.revoked_at IS DISTINCT FROM OLD.revoked_at
            OR NEW.revoked_by_user_id IS DISTINCT FROM OLD.revoked_by_user_id OR NEW.revocation_reason IS DISTINCT FROM OLD.revocation_reason))
       OR (OLD.used_at IS NOT NULL AND NEW.used_at IS DISTINCT FROM OLD.used_at)
       OR (OLD.expired_at IS NOT NULL AND NEW.expired_at IS DISTINCT FROM OLD.expired_at) THEN
        RAISE EXCEPTION 'assembly_proxies: datos de una transicion ya registrada'
            USING ERRCODE = '23514', CONSTRAINT = 'assembly_proxies_history_locked';
    END IF;

    IF NEW.status IS DISTINCT FROM OLD.status AND NOT (
           (OLD.status = 'PENDING_REVIEW' AND NEW.status IN ('APPROVED', 'REJECTED', 'REVOKED', 'EXPIRED'))
        OR (OLD.status = 'APPROVED'       AND NEW.status IN ('REVOKED', 'USED', 'EXPIRED'))) THEN
        RAISE EXCEPTION 'assembly_proxies: transicion % -> % no permitida', OLD.status, NEW.status
            USING ERRCODE = '23514', CONSTRAINT = 'assembly_proxies_transition';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_assembly_proxies_guard
    BEFORE UPDATE OR DELETE ON public.assembly_proxies
    FOR EACH ROW EXECUTE FUNCTION public.assembly_proxies_guard();

-- 3. Historial de eventos de los poderes (solo insercion).
CREATE TABLE public.assembly_proxy_events (
    id             uuid        NOT NULL,
    tenant_id      uuid        NOT NULL,
    proxy_id       uuid,
    assembly_id    uuid        NOT NULL,
    property_id    uuid,
    actor_user_id  uuid,
    actor_channel  varchar(10) NOT NULL,
    action         varchar(40) NOT NULL,
    result         varchar(10) NOT NULL,
    from_status    varchar(20),
    to_status      varchar(20),
    reason_code    varchar(60),
    occurred_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT assembly_proxy_events_pkey PRIMARY KEY (id),
    CONSTRAINT fk_assembly_proxy_events_proxy FOREIGN KEY (proxy_id) REFERENCES public.assembly_proxies (id),
    CONSTRAINT assembly_proxy_events_channel_check CHECK (actor_channel IN ('OWNER', 'ADMIN', 'SYSTEM')),
    CONSTRAINT assembly_proxy_events_result_check CHECK (result IN ('SUCCESS', 'DENIED')),
    CONSTRAINT assembly_proxy_events_action_check CHECK (action IN ('PROXY_REQUESTED', 'PROXY_DOCUMENT_REGISTERED',
        'PROXY_DOCUMENT_REPLACED', 'PROXY_APPROVED', 'PROXY_REJECTED', 'PROXY_REVOKED', 'PROXY_EXPIRED',
        'PROXY_TRANSITION_DENIED', 'PROXY_DOCUMENT_ACCESSED'))
);

CREATE INDEX ix_assembly_proxy_events_proxy ON public.assembly_proxy_events (proxy_id, occurred_at);
CREATE INDEX ix_assembly_proxy_events_tenant_assembly ON public.assembly_proxy_events (tenant_id, assembly_id, occurred_at);

-- Solo insercion para las rutas de la aplicacion. NO es inmutabilidad absoluta: el propietario de la tabla o un
-- superusuario pueden desactivar el trigger, y TRUNCATE requiere su propio trigger (incluido abajo).
CREATE FUNCTION public.assembly_proxy_events_append_only() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'assembly_proxy_events: historial de solo insercion'
        USING ERRCODE = '23514', CONSTRAINT = 'assembly_proxy_events_append_only';
END;
$$;

CREATE TRIGGER trg_assembly_proxy_events_append_only
    BEFORE UPDATE OR DELETE ON public.assembly_proxy_events
    FOR EACH ROW EXECUTE FUNCTION public.assembly_proxy_events_append_only();

CREATE TRIGGER trg_assembly_proxy_events_no_truncate
    BEFORE TRUNCATE ON public.assembly_proxy_events
    FOR EACH STATEMENT EXECUTE FUNCTION public.assembly_proxy_events_append_only();
