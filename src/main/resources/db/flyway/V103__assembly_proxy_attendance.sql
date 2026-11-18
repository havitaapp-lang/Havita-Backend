-- Fase 2 / P2 — Poderes en la asistencia (PREPARADO, NO ACTIVADO).
--
-- Migracion incremental posterior a V102. NO modifica V100/V101/V102 (solo anade objetos o sustituye, con
-- CREATE OR REPLACE / DROP + ADD, la funcion y el CHECK de eventos que V102 creo). Flyway sigue desactivado: solo se
-- aplica en bases aisladas o en una adopcion futura aprobada. Sin entidades JPA nuevas.
--
-- Invariantes que garantiza PostgreSQL:
--   * un poder USED tiene exactamente una asistencia, de su MISMA comunidad, asamblea y unidad, marcada como apoderado;
--   * una asistencia nueva marcada como apoderado tiene, al confirmar la transaccion, un poder USED que la referencia;
--   * la asistencia y la verificacion de identidad de un poder no se reescriben.

-- 1. Clave compuesta de la asistencia (trivialmente cierta: id es PK) para que la FK exija la misma unidad y asamblea.
ALTER TABLE public.assembly_attendees
    ADD CONSTRAINT uq_assembly_attendees_id_scope UNIQUE (id, tenant_id, assembly_id, property_id);

-- 2. Uso del poder: asistencia registrada y verificacion de identidad.
ALTER TABLE public.assembly_proxies
    ADD COLUMN attendee_id uuid,
    ADD COLUMN used_channel varchar(10),
    ADD COLUMN identity_verified_by_user_id uuid,
    ADD COLUMN identity_verified_at timestamptz;

ALTER TABLE public.assembly_proxies
    ADD CONSTRAINT uq_assembly_proxies_attendee UNIQUE (attendee_id),
    ADD CONSTRAINT fk_assembly_proxies_attendee FOREIGN KEY (attendee_id, tenant_id, assembly_id, property_id)
        REFERENCES public.assembly_attendees (id, tenant_id, assembly_id, property_id),
    ADD CONSTRAINT fk_assembly_proxies_identity_verified_by FOREIGN KEY (identity_verified_by_user_id) REFERENCES public.users (id),
    ADD CONSTRAINT assembly_proxies_used_channel_check CHECK (used_channel IS NULL OR used_channel IN ('PROXY', 'ADMIN')),
    -- USED si y solo si tiene asistencia; y entonces con canal. Si la registro la administracion, con verificacion.
    ADD CONSTRAINT assembly_proxies_used_attendee_check CHECK (
        ((status = 'USED') = (attendee_id IS NOT NULL))
        AND ((status = 'USED') = (used_channel IS NOT NULL))
        AND (used_channel IS DISTINCT FROM 'ADMIN' OR (identity_verified_by_user_id IS NOT NULL AND identity_verified_at IS NOT NULL))
        AND ((identity_verified_by_user_id IS NULL) = (identity_verified_at IS NULL)));

-- Lectura frecuente de P2: poderes efectivos (aprobados o usados) de una unidad.
CREATE INDEX ix_assembly_proxies_unit_effective ON public.assembly_proxies (property_id, assembly_id)
    WHERE status IN ('APPROVED', 'USED');

-- 3. Guardia de transiciones de V102, ampliada con las columnas de uso (mismo nombre: CREATE OR REPLACE).
CREATE OR REPLACE FUNCTION public.assembly_proxies_guard() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'assembly_proxies: los poderes no se eliminan (historial)'
            USING ERRCODE = '23514', CONSTRAINT = 'assembly_proxies_no_delete';
    END IF;

    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
       OR NEW.assembly_id IS DISTINCT FROM OLD.assembly_id OR NEW.property_id IS DISTINCT FROM OLD.property_id
       OR NEW.grantor_resident_id IS DISTINCT FROM OLD.grantor_resident_id OR NEW.grantor_user_id IS DISTINCT FROM OLD.grantor_user_id
       OR NEW.created_by_user_id IS DISTINCT FROM OLD.created_by_user_id OR NEW.created_channel IS DISTINCT FROM OLD.created_channel
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'assembly_proxies: columnas inmutables'
            USING ERRCODE = '23514', CONSTRAINT = 'assembly_proxies_immutable';
    END IF;

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

    IF (OLD.reviewed_at IS NOT NULL AND (NEW.reviewed_at IS DISTINCT FROM OLD.reviewed_at
            OR NEW.reviewed_by_user_id IS DISTINCT FROM OLD.reviewed_by_user_id OR NEW.review_decision IS DISTINCT FROM OLD.review_decision
            OR NEW.rejection_reason IS DISTINCT FROM OLD.rejection_reason))
       OR (OLD.revoked_at IS NOT NULL AND (NEW.revoked_at IS DISTINCT FROM OLD.revoked_at
            OR NEW.revoked_by_user_id IS DISTINCT FROM OLD.revoked_by_user_id OR NEW.revocation_reason IS DISTINCT FROM OLD.revocation_reason))
       OR (OLD.used_at IS NOT NULL AND NEW.used_at IS DISTINCT FROM OLD.used_at)
       OR (OLD.expired_at IS NOT NULL AND NEW.expired_at IS DISTINCT FROM OLD.expired_at)
       -- P2: asistencia, canal y verificacion de identidad, una vez fijados, no cambian.
       OR (OLD.attendee_id IS NOT NULL AND (NEW.attendee_id IS DISTINCT FROM OLD.attendee_id
            OR NEW.used_channel IS DISTINCT FROM OLD.used_channel
            OR NEW.identity_verified_by_user_id IS DISTINCT FROM OLD.identity_verified_by_user_id
            OR NEW.identity_verified_at IS DISTINCT FROM OLD.identity_verified_at)) THEN
        RAISE EXCEPTION 'assembly_proxies: datos de una transicion ya registrada'
            USING ERRCODE = '23514', CONSTRAINT = 'assembly_proxies_history_locked';
    END IF;

    -- P2: la asistencia, el canal y la verificacion solo se fijan al pasar de APPROVED a USED.
    IF (NEW.attendee_id IS DISTINCT FROM OLD.attendee_id OR NEW.used_channel IS DISTINCT FROM OLD.used_channel
        OR NEW.identity_verified_by_user_id IS DISTINCT FROM OLD.identity_verified_by_user_id
        OR NEW.identity_verified_at IS DISTINCT FROM OLD.identity_verified_at)
       AND NOT (OLD.status = 'APPROVED' AND NEW.status = 'USED') THEN
        RAISE EXCEPTION 'assembly_proxies: la asistencia solo se registra al usar un poder aprobado'
            USING ERRCODE = '23514', CONSTRAINT = 'assembly_proxies_use_locked';
    END IF;

    -- P2: la asistencia que usa el poder debe estar marcada como asistencia por apoderado.
    IF NEW.status = 'USED' AND OLD.status = 'APPROVED' AND NOT EXISTS (
            SELECT 1 FROM public.assembly_attendees a WHERE a.id = NEW.attendee_id AND a.is_power_of_attorney) THEN
        RAISE EXCEPTION 'assembly_proxies: la asistencia del poder debe ser por apoderado'
            USING ERRCODE = '23514', CONSTRAINT = 'assembly_proxies_attendee_kind';
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

-- 4. Una asistencia NUEVA (o que pasa a ser) por apoderado exige, al confirmar, un poder USED que la referencie. Las
--    asistencias historicas no se tocan (el trigger solo actua en INSERT o al cambiar el indicador a true).
CREATE FUNCTION public.assembly_attendees_proxy_required() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM public.assembly_proxies p WHERE p.attendee_id = NEW.id AND p.status = 'USED') THEN
        RAISE EXCEPTION 'assembly_attendees: asistencia por apoderado sin poder utilizado'
            USING ERRCODE = '23514', CONSTRAINT = 'assembly_attendees_proxy_required';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER trg_assembly_attendees_proxy_required_ins
    AFTER INSERT ON public.assembly_attendees
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW WHEN (NEW.is_power_of_attorney)
    EXECUTE FUNCTION public.assembly_attendees_proxy_required();

CREATE CONSTRAINT TRIGGER trg_assembly_attendees_proxy_required_upd
    AFTER UPDATE OF is_power_of_attorney ON public.assembly_attendees
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW WHEN (NEW.is_power_of_attorney AND NOT OLD.is_power_of_attorney)
    EXECUTE FUNCTION public.assembly_attendees_proxy_required();

-- 5. Acciones de auditoria de P2 (el CHECK de V102 se sustituye por uno que incluye las anteriores y las nuevas).
ALTER TABLE public.assembly_proxy_events DROP CONSTRAINT assembly_proxy_events_action_check;
ALTER TABLE public.assembly_proxy_events ADD CONSTRAINT assembly_proxy_events_action_check CHECK (action IN (
    'PROXY_REQUESTED', 'PROXY_DOCUMENT_REGISTERED', 'PROXY_DOCUMENT_REPLACED', 'PROXY_APPROVED', 'PROXY_REJECTED',
    'PROXY_REVOKED', 'PROXY_EXPIRED', 'PROXY_TRANSITION_DENIED', 'PROXY_DOCUMENT_ACCESSED',
    'PROXY_USED', 'PROXY_ATTENDANCE_REGISTERED', 'PROXY_IDENTITY_VERIFIED', 'PROXY_ATTENDANCE_DENIED',
    'DELEGATION_BLOCKED_BY_PROXY', 'ATTENDANCE_BLOCKED_BY_PROXY'));
ALTER TABLE public.assembly_proxy_events DROP CONSTRAINT assembly_proxy_events_channel_check;
ALTER TABLE public.assembly_proxy_events ADD CONSTRAINT assembly_proxy_events_channel_check
    CHECK (actor_channel IN ('OWNER', 'ADMIN', 'SYSTEM', 'PROXY', 'TENANT'));
