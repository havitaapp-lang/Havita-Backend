-- Fase 2 / P4 — Participaciones de voto, voto mediante poder, voto asistido, anulacion auditable y auditoria de votos
-- (PREPARADO, NO ACTIVADO).
--
-- Migracion incremental posterior a V103. NO modifica V100-V103 ni ningun dato existente: solo anade tablas, columnas
-- (nulas), restricciones nuevas y triggers que actuan sobre filas NUEVAS. Flyway sigue desactivado en la aplicacion:
-- solo se aplica en bases aisladas de prueba o en una adopcion futura aprobada. Sin entidades JPA nuevas (Hibernate
-- ddl-auto=update no crea nada de esto en ninguna base existente).
--
-- Invariantes que garantiza PostgreSQL desde esta version (para votos NUEVOS; los historicos no se reescriben):
--   V-1  una unidad tiene como maximo UNA participacion vigente por pregunta: indice unico parcial de participaciones y,
--        para cualquier INSERT en poll_votes (con o sin participacion, tambien SQL directo), un trigger serializado que
--        rechaza un segundo voto vigente de la unidad en la pregunta. En SINGLE_CHOICE, una sola opcion por participacion.
--   V-2  cada participacion identifica actor, titulo de representacion (relacion, delegacion o poder), asistencia,
--        comunidad, asamblea, pregunta y unidad, coherentes entre si (FK compuestas + comprobaciones del trigger).
--   V-3  solo un voto asistido puede anularse, solo VALID -> VOIDED, con motivo, con la pregunta abierta y la asamblea
--        en curso; los votos de una participacion anulada se conservan y dejan de contar.
--   V-4  una asamblea finalizada no vuelve a otro estado.
--   Los votos (poll_votes) no se actualizan ni se borran; las participaciones no se borran; los eventos son de solo
--   insercion. Esto NO es inmutabilidad absoluta: el propietario de las tablas o un superusuario pueden desactivar
--   triggers.

-- ─────────────────────────────────────────────────────────────────────────────────────────────────────────────────
-- 1. Claves compuestas auxiliares (trivialmente ciertas: id es PK) para que las FK exijan la misma comunidad,
--    asamblea y unidad.
-- ─────────────────────────────────────────────────────────────────────────────────────────────────────────────────
ALTER TABLE public.polls ADD CONSTRAINT uq_polls_id_scope UNIQUE (id, tenant_id, assembly_id);
ALTER TABLE public.assembly_proxies ADD CONSTRAINT uq_assembly_proxies_id_scope UNIQUE (id, tenant_id, assembly_id, property_id);
ALTER TABLE public.voting_delegations ADD CONSTRAINT uq_voting_delegations_id_scope UNIQUE (id, tenant_id, property_id);

-- Administracion vigente de una comunidad, evaluada en PostgreSQL (misma regla que AccessValidator.lockCommunityAdministrator).
CREATE FUNCTION public.havita_is_active_community_admin(p_user uuid, p_tenant uuid) RETURNS boolean
    LANGUAGE sql STABLE AS $$
    SELECT EXISTS (
        SELECT 1 FROM public.memberships m JOIN public.users u ON u.id = m.user_id
        WHERE m.user_id = p_user AND m.status = 'ACTIVE' AND u.status = 'ACTIVE'
          AND ((m.role = 'COMMUNITY_ADMIN' AND m.tenant_id = p_tenant)
               OR (m.role = 'SUPER_ADMIN' AND (m.tenant_id IS NULL OR m.tenant_id = p_tenant))));
$$;

-- ─────────────────────────────────────────────────────────────────────────────────────────────────────────────────
-- 2. Participaciones: una fila por unidad que vota una pregunta (sus opciones estan en poll_votes).
-- ─────────────────────────────────────────────────────────────────────────────────────────────────────────────────
CREATE TABLE public.poll_participations (
    id                            uuid         NOT NULL,
    tenant_id                     uuid         NOT NULL,
    assembly_id                   uuid         NOT NULL,
    poll_id                       uuid         NOT NULL,
    property_id                   uuid         NOT NULL,
    -- Asistencia que habilita el voto (para un poder, la asistencia por apoderado de assembly_proxies.attendee_id).
    attendee_id                   uuid         NOT NULL,
    representation_type           varchar(30)  NOT NULL,
    -- Actor real: la cuenta autenticada que ejecuto la operacion (representante, apoderado o administrador que asiste).
    actor_user_id                 uuid         NOT NULL,
    -- Relacion de residente que vota (OWNER, CO_OWNER, TENANT_DELEGATE).
    resident_id                   uuid,
    -- Titulo del arrendatario: la delegacion ACTIVA a su favor (la persona representada es su owner_user_id).
    delegation_id                 uuid,
    -- Titulo del apoderado: el poder USED; la persona representada es el otorgante.
    proxy_id                      uuid,
    grantor_resident_id           uuid,
    -- Voto asistido: verificacion presencial de identidad y confirmacion de que el voto lo expreso el apoderado.
    identity_verified_by_user_id  uuid,
    identity_verified_at          timestamptz,
    identity_verification_method  varchar(30),
    expressed_by_proxy_confirmed  boolean,
    status                        varchar(10)  NOT NULL DEFAULT 'VALID',
    cast_at                       timestamptz  NOT NULL,
    -- Transaccion que creo la participacion: sus votos solo pueden insertarse en esa misma transaccion.
    created_txid                  bigint       NOT NULL DEFAULT txid_current(),
    voided_by_user_id             uuid,
    voided_at                     timestamptz,
    void_reason                   varchar(500),
    CONSTRAINT poll_participations_pkey PRIMARY KEY (id),
    CONSTRAINT uq_poll_participations_id_scope UNIQUE (id, tenant_id, poll_id, property_id),
    CONSTRAINT fk_poll_participations_tenant FOREIGN KEY (tenant_id) REFERENCES public.tenants (id),
    CONSTRAINT fk_poll_participations_assembly FOREIGN KEY (assembly_id, tenant_id) REFERENCES public.assemblies (id, tenant_id),
    CONSTRAINT fk_poll_participations_poll FOREIGN KEY (poll_id, tenant_id, assembly_id)
        REFERENCES public.polls (id, tenant_id, assembly_id),
    CONSTRAINT fk_poll_participations_property FOREIGN KEY (property_id, tenant_id) REFERENCES public.properties (id, tenant_id),
    CONSTRAINT fk_poll_participations_attendee FOREIGN KEY (attendee_id, tenant_id, assembly_id, property_id)
        REFERENCES public.assembly_attendees (id, tenant_id, assembly_id, property_id),
    CONSTRAINT fk_poll_participations_resident FOREIGN KEY (resident_id, tenant_id, property_id)
        REFERENCES public.residents (id, tenant_id, property_id),
    CONSTRAINT fk_poll_participations_grantor FOREIGN KEY (grantor_resident_id, tenant_id, property_id)
        REFERENCES public.residents (id, tenant_id, property_id),
    CONSTRAINT fk_poll_participations_proxy FOREIGN KEY (proxy_id, tenant_id, assembly_id, property_id)
        REFERENCES public.assembly_proxies (id, tenant_id, assembly_id, property_id),
    CONSTRAINT fk_poll_participations_delegation FOREIGN KEY (delegation_id, tenant_id, property_id)
        REFERENCES public.voting_delegations (id, tenant_id, property_id),
    CONSTRAINT fk_poll_participations_actor FOREIGN KEY (actor_user_id) REFERENCES public.users (id),
    CONSTRAINT fk_poll_participations_verified_by FOREIGN KEY (identity_verified_by_user_id) REFERENCES public.users (id),
    CONSTRAINT fk_poll_participations_voided_by FOREIGN KEY (voided_by_user_id) REFERENCES public.users (id),
    CONSTRAINT poll_participations_type_check CHECK (representation_type IN
        ('OWNER', 'CO_OWNER', 'TENANT_DELEGATE', 'ACCOUNT_PROXY', 'ADMIN_ASSISTED_PROXY')),
    CONSTRAINT poll_participations_status_check CHECK (status IN ('VALID', 'VOIDED')),
    -- Titulo coherente con el tipo.
    CONSTRAINT poll_participations_title_check CHECK (
        CASE
            WHEN representation_type IN ('OWNER', 'CO_OWNER') THEN
                resident_id IS NOT NULL AND delegation_id IS NULL AND proxy_id IS NULL AND grantor_resident_id IS NULL
            WHEN representation_type = 'TENANT_DELEGATE' THEN
                resident_id IS NOT NULL AND delegation_id IS NOT NULL AND proxy_id IS NULL AND grantor_resident_id IS NULL
            ELSE
                resident_id IS NULL AND delegation_id IS NULL AND proxy_id IS NOT NULL AND grantor_resident_id IS NOT NULL
        END),
    -- Verificacion presencial: solo y siempre en el voto asistido, hecha por el propio actor.
    CONSTRAINT poll_participations_assisted_check CHECK (
        CASE
            WHEN representation_type = 'ADMIN_ASSISTED_PROXY' THEN
                identity_verified_by_user_id IS NOT NULL AND identity_verified_by_user_id = actor_user_id
                AND identity_verified_at IS NOT NULL AND identity_verification_method = 'IN_PERSON_DOCUMENT'
                AND expressed_by_proxy_confirmed IS TRUE
            ELSE
                identity_verified_by_user_id IS NULL AND identity_verified_at IS NULL
                AND identity_verification_method IS NULL AND expressed_by_proxy_confirmed IS NULL
        END),
    -- Anulacion: con quien, cuando y motivo no vacio; solo para votos asistidos (P4-R2).
    CONSTRAINT poll_participations_void_check CHECK (
        ((status = 'VOIDED') = (voided_by_user_id IS NOT NULL AND voided_at IS NOT NULL
                                AND void_reason IS NOT NULL AND btrim(void_reason) <> ''))
        AND (status = 'VALID' OR representation_type = 'ADMIN_ASSISTED_PROXY')
        AND (status = 'VOIDED' OR (voided_by_user_id IS NULL AND voided_at IS NULL AND void_reason IS NULL)))
);

-- V-1: una sola participacion VIGENTE por pregunta y unidad (aplica a todos los tipos de pregunta: en una pregunta de
-- seleccion multiple la unidad participa una vez, con varias opciones dentro de la misma participacion).
CREATE UNIQUE INDEX uq_poll_participation_valid ON public.poll_participations (poll_id, property_id) WHERE status = 'VALID';
CREATE INDEX ix_poll_participations_tenant_poll ON public.poll_participations (tenant_id, poll_id);
CREATE INDEX ix_poll_participations_proxy ON public.poll_participations (proxy_id) WHERE proxy_id IS NOT NULL;
CREATE INDEX ix_poll_participations_attendee ON public.poll_participations (attendee_id);

-- Comprobaciones de autorizacion y estado al crear una participacion (defensa en profundidad: la aplicacion ya las hace
-- con los bloqueos tomados; aqui se rechaza cualquier escritura que no las cumpla, venga de donde venga).
CREATE FUNCTION public.poll_participations_before_insert() RETURNS trigger
    LANGUAGE plpgsql AS $$
DECLARE
    v_poll_status     text;
    v_assembly_status text;
    v_att_resident    uuid;
    v_att_poa         boolean;
    v_res             record;
    v_del             record;
    v_proxy           record;
BEGIN
    IF NEW.status <> 'VALID' THEN
        RAISE EXCEPTION 'poll_participations: una participacion nace vigente'
            USING ERRCODE = '23514', CONSTRAINT = 'poll_participations_insert_valid';
    END IF;
    NEW.created_txid := txid_current();

    IF NOT EXISTS (SELECT 1 FROM public.users u WHERE u.id = NEW.actor_user_id AND u.status = 'ACTIVE') THEN
        RAISE EXCEPTION 'poll_participations: actor sin cuenta activa'
            USING ERRCODE = '23514', CONSTRAINT = 'poll_participations_actor_active';
    END IF;

    SELECT p.status INTO v_poll_status FROM public.polls p WHERE p.id = NEW.poll_id;
    IF v_poll_status IS DISTINCT FROM 'LIVE' THEN
        RAISE EXCEPTION 'poll_participations: la pregunta no esta abierta'
            USING ERRCODE = '23514', CONSTRAINT = 'poll_participations_poll_live';
    END IF;
    SELECT a.status INTO v_assembly_status FROM public.assemblies a WHERE a.id = NEW.assembly_id;
    IF v_assembly_status IS NULL OR v_assembly_status = 'ENDED'
       OR (NEW.representation_type = 'ADMIN_ASSISTED_PROXY' AND v_assembly_status <> 'IN_PROGRESS') THEN
        RAISE EXCEPTION 'poll_participations: la asamblea no admite votos'
            USING ERRCODE = '23514', CONSTRAINT = 'poll_participations_assembly_open';
    END IF;

    SELECT a.resident_id, a.is_power_of_attorney INTO v_att_resident, v_att_poa
        FROM public.assembly_attendees a WHERE a.id = NEW.attendee_id;

    IF NEW.representation_type IN ('OWNER', 'CO_OWNER', 'TENANT_DELEGATE') THEN
        SELECT r.user_id, r.status, lower(r.type) AS type INTO v_res FROM public.residents r WHERE r.id = NEW.resident_id;
        IF v_att_poa OR v_att_resident IS DISTINCT FROM NEW.resident_id OR v_res.status IS DISTINCT FROM 'ACTIVE'
           OR v_res.user_id IS DISTINCT FROM NEW.actor_user_id
           OR (NEW.representation_type IN ('OWNER', 'CO_OWNER') AND v_res.type IS DISTINCT FROM 'propietario')
           OR (NEW.representation_type = 'TENANT_DELEGATE' AND v_res.type IS DISTINCT FROM 'arrendatario') THEN
            RAISE EXCEPTION 'poll_participations: el actor no es el representante registrado de la unidad'
                USING ERRCODE = '23514', CONSTRAINT = 'poll_participations_representative';
        END IF;
        IF NEW.representation_type = 'TENANT_DELEGATE' THEN
            SELECT d.status, d.delegate_user_id INTO v_del FROM public.voting_delegations d WHERE d.id = NEW.delegation_id;
            IF v_del.status IS DISTINCT FROM 'ACTIVE' OR v_del.delegate_user_id IS DISTINCT FROM NEW.actor_user_id THEN
                RAISE EXCEPTION 'poll_participations: sin delegacion activa a favor del actor'
                    USING ERRCODE = '23514', CONSTRAINT = 'poll_participations_delegation';
            END IF;
        END IF;
    ELSE
        SELECT p.status, p.attendee_id, p.proxy_user_id, p.grantor_resident_id INTO v_proxy
            FROM public.assembly_proxies p WHERE p.id = NEW.proxy_id;
        IF v_proxy.status IS DISTINCT FROM 'USED' OR v_proxy.attendee_id IS DISTINCT FROM NEW.attendee_id
           OR v_proxy.grantor_resident_id IS DISTINCT FROM NEW.grantor_resident_id OR v_att_poa IS NOT TRUE THEN
            RAISE EXCEPTION 'poll_participations: el poder no esta utilizado en la asistencia de la unidad'
                USING ERRCODE = '23514', CONSTRAINT = 'poll_participations_proxy';
        END IF;
        -- P4-R1: el apoderado con cuenta vota el mismo; el voto asistido es solo para apoderados externos (sin cuenta)
        -- y lo registra una administracion vigente de la comunidad.
        IF NEW.representation_type = 'ACCOUNT_PROXY'
           AND (v_proxy.proxy_user_id IS NULL OR v_proxy.proxy_user_id <> NEW.actor_user_id) THEN
            RAISE EXCEPTION 'poll_participations: el actor no es el apoderado del poder'
                USING ERRCODE = '23514', CONSTRAINT = 'poll_participations_account_proxy';
        END IF;
        IF NEW.representation_type = 'ADMIN_ASSISTED_PROXY'
           AND (v_proxy.proxy_user_id IS NOT NULL
                OR NOT public.havita_is_active_community_admin(NEW.actor_user_id, NEW.tenant_id)) THEN
            RAISE EXCEPTION 'poll_participations: voto asistido solo para apoderados externos y por la administracion'
                USING ERRCODE = '23514', CONSTRAINT = 'poll_participations_assisted';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_poll_participations_before_insert
    BEFORE INSERT ON public.poll_participations
    FOR EACH ROW EXECUTE FUNCTION public.poll_participations_before_insert();

-- Solo VALID -> VOIDED (con la pregunta abierta, la asamblea en curso y una administracion vigente); nada mas cambia y
-- nada se borra.
CREATE FUNCTION public.poll_participations_guard() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'poll_participations: las participaciones no se eliminan (historial)'
            USING ERRCODE = '23514', CONSTRAINT = 'poll_participations_no_delete';
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
       OR NEW.assembly_id IS DISTINCT FROM OLD.assembly_id OR NEW.poll_id IS DISTINCT FROM OLD.poll_id
       OR NEW.property_id IS DISTINCT FROM OLD.property_id OR NEW.attendee_id IS DISTINCT FROM OLD.attendee_id
       OR NEW.representation_type IS DISTINCT FROM OLD.representation_type
       OR NEW.actor_user_id IS DISTINCT FROM OLD.actor_user_id OR NEW.resident_id IS DISTINCT FROM OLD.resident_id
       OR NEW.delegation_id IS DISTINCT FROM OLD.delegation_id OR NEW.proxy_id IS DISTINCT FROM OLD.proxy_id
       OR NEW.grantor_resident_id IS DISTINCT FROM OLD.grantor_resident_id
       OR NEW.identity_verified_by_user_id IS DISTINCT FROM OLD.identity_verified_by_user_id
       OR NEW.identity_verified_at IS DISTINCT FROM OLD.identity_verified_at
       OR NEW.identity_verification_method IS DISTINCT FROM OLD.identity_verification_method
       OR NEW.expressed_by_proxy_confirmed IS DISTINCT FROM OLD.expressed_by_proxy_confirmed
       OR NEW.cast_at IS DISTINCT FROM OLD.cast_at OR NEW.created_txid IS DISTINCT FROM OLD.created_txid THEN
        RAISE EXCEPTION 'poll_participations: columnas inmutables'
            USING ERRCODE = '23514', CONSTRAINT = 'poll_participations_immutable';
    END IF;
    IF OLD.status = 'VOIDED' THEN
        RAISE EXCEPTION 'poll_participations: una participacion anulada no cambia'
            USING ERRCODE = '23514', CONSTRAINT = 'poll_participations_voided_locked';
    END IF;
    IF NEW.status IS DISTINCT FROM OLD.status THEN
        IF NOT (OLD.status = 'VALID' AND NEW.status = 'VOIDED') THEN
            RAISE EXCEPTION 'poll_participations: transicion % -> % no permitida', OLD.status, NEW.status
                USING ERRCODE = '23514', CONSTRAINT = 'poll_participations_transition';
        END IF;
        IF NOT EXISTS (SELECT 1 FROM public.polls p WHERE p.id = NEW.poll_id AND p.status = 'LIVE')
           OR NOT EXISTS (SELECT 1 FROM public.assemblies a WHERE a.id = NEW.assembly_id AND a.status = 'IN_PROGRESS') THEN
            RAISE EXCEPTION 'poll_participations: solo se anula con la pregunta abierta y la asamblea en curso'
                USING ERRCODE = '23514', CONSTRAINT = 'poll_participations_void_window';
        END IF;
        IF NOT public.havita_is_active_community_admin(NEW.voided_by_user_id, NEW.tenant_id) THEN
            RAISE EXCEPTION 'poll_participations: solo una administracion vigente anula'
                USING ERRCODE = '23514', CONSTRAINT = 'poll_participations_void_admin';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_poll_participations_guard
    BEFORE UPDATE OR DELETE ON public.poll_participations
    FOR EACH ROW EXECUTE FUNCTION public.poll_participations_guard();

CREATE FUNCTION public.havita_reject_truncate() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '%: historial, TRUNCATE no permitido', TG_TABLE_NAME
        USING ERRCODE = '23514', CONSTRAINT = TG_TABLE_NAME || '_no_truncate';
END;
$$;

CREATE TRIGGER trg_poll_participations_no_truncate
    BEFORE TRUNCATE ON public.poll_participations
    FOR EACH STATEMENT EXECUTE FUNCTION public.havita_reject_truncate();

-- Una participacion sin votos no existe: al confirmar la transaccion debe tener al menos una opcion.
CREATE FUNCTION public.poll_participations_requires_vote() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM public.poll_votes v WHERE v.participation_id = NEW.id) THEN
        RAISE EXCEPTION 'poll_participations: participacion sin votos'
            USING ERRCODE = '23514', CONSTRAINT = 'poll_participations_requires_vote';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER trg_poll_participations_requires_vote
    AFTER INSERT ON public.poll_participations
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION public.poll_participations_requires_vote();

-- ─────────────────────────────────────────────────────────────────────────────────────────────────────────────────
-- 3. Votos: referencia (nula para los historicos) a su participacion; unicidad de voto vigente para votos NUEVOS.
-- ─────────────────────────────────────────────────────────────────────────────────────────────────────────────────
ALTER TABLE public.poll_votes ADD COLUMN participation_id uuid;
ALTER TABLE public.poll_votes ADD CONSTRAINT fk_poll_votes_participation
    FOREIGN KEY (participation_id, tenant_id, poll_id, property_id)
    REFERENCES public.poll_participations (id, tenant_id, poll_id, property_id);
CREATE INDEX ix_poll_votes_participation ON public.poll_votes (participation_id) WHERE participation_id IS NOT NULL;
CREATE INDEX ix_poll_votes_poll_property ON public.poll_votes (poll_id, property_id);

-- V-1 para cualquier INSERT nuevo (rutas de la aplicacion, SQL directo o una ruta futura que no siga el protocolo):
-- con un bloqueo de transaccion por (pregunta, unidad), un segundo voto VIGENTE de la unidad en la pregunta se rechaza
-- (23505, como una violacion de unicidad). Vigente = sin participacion (historico o directo) o de una participacion VALID.
-- Los votos de una participacion solo se insertan en la transaccion que la creo; en SINGLE_CHOICE, una sola opcion.
CREATE FUNCTION public.poll_votes_before_insert() RETURNS trigger
    LANGUAGE plpgsql AS $$
DECLARE
    v_poll   record;
    v_part   record;
BEGIN
    PERFORM pg_advisory_xact_lock(hashtextextended('poll-vote:' || NEW.poll_id::text || ':' || NEW.property_id::text, 0));

    SELECT p.tenant_id, upper(p.type) AS type INTO v_poll FROM public.polls p WHERE p.id = NEW.poll_id;
    IF v_poll.tenant_id IS DISTINCT FROM NEW.tenant_id
       OR NOT EXISTS (SELECT 1 FROM public.poll_options o WHERE o.id = NEW.poll_option_id AND o.poll_id = NEW.poll_id)
       OR NOT EXISTS (SELECT 1 FROM public.properties x WHERE x.id = NEW.property_id AND x.tenant_id = NEW.tenant_id) THEN
        RAISE EXCEPTION 'poll_votes: pregunta, opcion y unidad deben ser de la misma comunidad y pregunta'
            USING ERRCODE = '23514', CONSTRAINT = 'poll_votes_scope';
    END IF;

    IF NEW.participation_id IS NULL THEN
        IF EXISTS (SELECT 1 FROM public.poll_votes v LEFT JOIN public.poll_participations pp ON pp.id = v.participation_id
                   WHERE v.poll_id = NEW.poll_id AND v.property_id = NEW.property_id
                     AND (v.participation_id IS NULL OR pp.status = 'VALID')) THEN
            RAISE EXCEPTION 'poll_votes: la unidad ya tiene un voto vigente en esta pregunta'
                USING ERRCODE = '23505', CONSTRAINT = 'poll_votes_one_participation_per_unit';
        END IF;
        RETURN NEW;
    END IF;

    SELECT pp.status, pp.created_txid INTO v_part FROM public.poll_participations pp WHERE pp.id = NEW.participation_id;
    IF v_part.status IS DISTINCT FROM 'VALID' OR v_part.created_txid IS DISTINCT FROM txid_current() THEN
        RAISE EXCEPTION 'poll_votes: los votos se registran junto con su participacion vigente'
            USING ERRCODE = '23514', CONSTRAINT = 'poll_votes_participation_open';
    END IF;
    IF EXISTS (SELECT 1 FROM public.poll_votes v LEFT JOIN public.poll_participations pp ON pp.id = v.participation_id
               WHERE v.poll_id = NEW.poll_id AND v.property_id = NEW.property_id
                 AND (v.participation_id IS NULL OR (v.participation_id <> NEW.participation_id AND pp.status = 'VALID'))) THEN
        RAISE EXCEPTION 'poll_votes: la unidad ya tiene un voto vigente en esta pregunta'
            USING ERRCODE = '23505', CONSTRAINT = 'poll_votes_one_participation_per_unit';
    END IF;
    IF v_poll.type = 'SINGLE_CHOICE'
       AND EXISTS (SELECT 1 FROM public.poll_votes v WHERE v.participation_id = NEW.participation_id) THEN
        RAISE EXCEPTION 'poll_votes: una pregunta de opcion unica admite una sola opcion por unidad'
            USING ERRCODE = '23505', CONSTRAINT = 'poll_votes_single_choice';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_poll_votes_before_insert
    BEFORE INSERT ON public.poll_votes
    FOR EACH ROW EXECUTE FUNCTION public.poll_votes_before_insert();

-- El voto original se conserva: ni UPDATE (cambiar la opcion en silencio) ni DELETE, tampoco de los historicos.
CREATE FUNCTION public.poll_votes_immutable() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'poll_votes: los votos no se modifican ni se eliminan'
        USING ERRCODE = '23514', CONSTRAINT = 'poll_votes_immutable';
END;
$$;

CREATE TRIGGER trg_poll_votes_immutable
    BEFORE UPDATE OR DELETE ON public.poll_votes
    FOR EACH ROW EXECUTE FUNCTION public.poll_votes_immutable();

-- ─────────────────────────────────────────────────────────────────────────────────────────────────────────────────
-- 4. V-4: una asamblea finalizada no se reabre (ni por startAssembly ni por otra escritura).
-- ─────────────────────────────────────────────────────────────────────────────────────────────────────────────────
CREATE FUNCTION public.assemblies_no_reopen() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status = 'ENDED' AND NEW.status IS DISTINCT FROM 'ENDED' THEN
        RAISE EXCEPTION 'assemblies: una asamblea finalizada no se reabre'
            USING ERRCODE = '23514', CONSTRAINT = 'assemblies_no_reopen';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_assemblies_no_reopen
    BEFORE UPDATE OF status ON public.assemblies
    FOR EACH ROW EXECUTE FUNCTION public.assemblies_no_reopen();

-- ─────────────────────────────────────────────────────────────────────────────────────────────────────────────────
-- 5. Auditoria propia de la votacion (P4-R5): no se reutiliza assembly_proxy_events.
-- ─────────────────────────────────────────────────────────────────────────────────────────────────────────────────
CREATE TABLE public.assembly_vote_events (
    id                      uuid         NOT NULL,
    tenant_id               uuid         NOT NULL,
    assembly_id             uuid         NOT NULL,
    poll_id                 uuid,
    property_id             uuid,
    participation_id        uuid,
    actor_user_id           uuid,
    -- Persona representada: relacion de residente (propietario que vota, otorgante del poder) cuando se conoce.
    represented_resident_id uuid,
    representation_type     varchar(30),
    proxy_id                uuid,
    delegation_id           uuid,
    action                  varchar(40)  NOT NULL,
    result                  varchar(10)  NOT NULL,
    reason_code             varchar(60),
    -- Motivo escrito por la administracion (anulacion). Nunca documentos, tokens ni numeros de identificacion.
    reason                  varchar(500),
    occurred_at             timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT assembly_vote_events_pkey PRIMARY KEY (id),
    CONSTRAINT fk_assembly_vote_events_tenant FOREIGN KEY (tenant_id) REFERENCES public.tenants (id),
    CONSTRAINT fk_assembly_vote_events_assembly FOREIGN KEY (assembly_id, tenant_id) REFERENCES public.assemblies (id, tenant_id),
    CONSTRAINT fk_assembly_vote_events_poll FOREIGN KEY (poll_id, tenant_id, assembly_id)
        REFERENCES public.polls (id, tenant_id, assembly_id),
    CONSTRAINT fk_assembly_vote_events_property FOREIGN KEY (property_id, tenant_id) REFERENCES public.properties (id, tenant_id),
    CONSTRAINT fk_assembly_vote_events_participation FOREIGN KEY (participation_id) REFERENCES public.poll_participations (id),
    CONSTRAINT fk_assembly_vote_events_actor FOREIGN KEY (actor_user_id) REFERENCES public.users (id),
    CONSTRAINT fk_assembly_vote_events_represented FOREIGN KEY (represented_resident_id) REFERENCES public.residents (id),
    CONSTRAINT fk_assembly_vote_events_proxy FOREIGN KEY (proxy_id) REFERENCES public.assembly_proxies (id),
    CONSTRAINT fk_assembly_vote_events_delegation FOREIGN KEY (delegation_id) REFERENCES public.voting_delegations (id),
    CONSTRAINT assembly_vote_events_action_check CHECK (action IN ('VOTE_CAST', 'ASSISTED_VOTE_CAST', 'ASSISTED_VOTE_VOIDED',
        'DUPLICATE_VOTE_REJECTED', 'UNAUTHORIZED_VOTE_REJECTED', 'VOID_REJECTED', 'ASSEMBLY_TRANSITION_REJECTED')),
    CONSTRAINT assembly_vote_events_result_check CHECK (result IN ('SUCCESS', 'DENIED')),
    CONSTRAINT assembly_vote_events_result_action_check CHECK (
        (result = 'SUCCESS') = (action IN ('VOTE_CAST', 'ASSISTED_VOTE_CAST', 'ASSISTED_VOTE_VOIDED'))),
    CONSTRAINT assembly_vote_events_type_check CHECK (representation_type IS NULL OR representation_type IN
        ('OWNER', 'CO_OWNER', 'TENANT_DELEGATE', 'ACCOUNT_PROXY', 'ADMIN_ASSISTED_PROXY')),
    -- Un exito identifica siempre actor, pregunta, unidad, participacion y tipo; una anulacion, ademas, su motivo.
    CONSTRAINT assembly_vote_events_success_check CHECK (result = 'DENIED' OR (actor_user_id IS NOT NULL
        AND poll_id IS NOT NULL AND property_id IS NOT NULL AND participation_id IS NOT NULL AND representation_type IS NOT NULL)),
    CONSTRAINT assembly_vote_events_void_reason_check CHECK (action <> 'ASSISTED_VOTE_VOIDED'
        OR (reason IS NOT NULL AND btrim(reason) <> ''))
);

CREATE INDEX ix_assembly_vote_events_tenant_assembly ON public.assembly_vote_events (tenant_id, assembly_id, occurred_at);
CREATE INDEX ix_assembly_vote_events_poll ON public.assembly_vote_events (poll_id, occurred_at) WHERE poll_id IS NOT NULL;
CREATE INDEX ix_assembly_vote_events_participation ON public.assembly_vote_events (participation_id) WHERE participation_id IS NOT NULL;

CREATE FUNCTION public.assembly_vote_events_append_only() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'assembly_vote_events: historial de solo insercion'
        USING ERRCODE = '23514', CONSTRAINT = 'assembly_vote_events_append_only';
END;
$$;

CREATE TRIGGER trg_assembly_vote_events_append_only
    BEFORE UPDATE OR DELETE ON public.assembly_vote_events
    FOR EACH ROW EXECUTE FUNCTION public.assembly_vote_events_append_only();

CREATE TRIGGER trg_assembly_vote_events_no_truncate
    BEFORE TRUNCATE ON public.assembly_vote_events
    FOR EACH STATEMENT EXECUTE FUNCTION public.havita_reject_truncate();
