-- Fase 2 / P5-A — Nueva emision de un voto asistido despues de su anulacion (PREPARADO, NO ACTIVADO).
--
-- Migracion incremental posterior a V104. NO modifica V100-V104 ni ningun dato existente: anade una columna nula, una
-- FK, un indice y triggers que solo actuan sobre filas NUEVAS; sustituye (DROP + ADD) los CHECK de acciones de
-- assembly_vote_events para incluir la nueva accion, conservando todas las anteriores. Flyway sigue desactivado.
--
-- Decision aprobada (P5): tras anular un voto asistido, con la pregunta abierta y la asamblea en curso, puede
-- registrarse un NUEVO voto asistido del mismo apoderado externo para la misma unidad y pregunta. Invariantes:
--   * la participacion anulada se conserva (V104 ya impide borrarla, modificar sus votos o revertir la anulacion);
--   * la nueva participacion es otra fila (otro id) que referencia a la anterior: P1 <- P2 <- P3 ... (cadena lineal);
--   * cada participacion es sustituida como maximo una vez (indice unico) y solo si esta ANULADA;
--   * la referencia es de la MISMA comunidad, pregunta y unidad (FK compuesta) y del MISMO poder;
--   * solo votos asistidos (ADMIN_ASSISTED_PROXY) participan en la cadena;
--   * una unidad que ya tiene participaciones en la pregunta solo puede volver a participar continuando la cadena;
--   * sigue habiendo como maximo una participacion VIGENTE por unidad y pregunta (uq_poll_participation_valid, V104).

-- Unicidad de opcion en poll_votes. La restriccion de V100 uq_property_poll_option (poll_id, property_id,
-- poll_option_id) abarca TODAS las filas, tambien las de una participacion anulada: impediria volver a elegir la
-- misma opcion en una nueva emision. Se sustituye por dos indices equivalentes sin perder ninguna garantia:
--   * votos sin participacion (historicos y escrituras directas): exactamente la misma unicidad que antes;
--   * votos con participacion: una opcion como maximo una vez por participacion.
-- La unicidad por unidad y pregunta de los votos VIGENTES la siguen garantizando uq_poll_participation_valid y el
-- trigger poll_votes_before_insert (V104). No se modifica ni se borra ninguna fila.
-- Nota para la adopcion: con este esquema la aplicacion debe arrancar con ddl-auto=validate (nunca update), porque
-- la entidad PollVote todavia declara uq_property_poll_option y "update" intentaria recrearla.
ALTER TABLE public.poll_votes DROP CONSTRAINT uq_property_poll_option;
CREATE UNIQUE INDEX uq_poll_votes_legacy_option ON public.poll_votes (poll_id, property_id, poll_option_id)
    WHERE participation_id IS NULL;
CREATE UNIQUE INDEX uq_poll_votes_participation_option ON public.poll_votes (participation_id, poll_option_id)
    WHERE participation_id IS NOT NULL;

ALTER TABLE public.poll_participations ADD COLUMN supersedes_participation_id uuid;

ALTER TABLE public.poll_participations ADD CONSTRAINT fk_poll_participations_supersedes
    FOREIGN KEY (supersedes_participation_id, tenant_id, poll_id, property_id)
    REFERENCES public.poll_participations (id, tenant_id, poll_id, property_id);

-- Una participacion anulada se sustituye como maximo una vez (dos nuevas emisiones concurrentes: una sola entra).
CREATE UNIQUE INDEX uq_poll_participations_supersedes ON public.poll_participations (supersedes_participation_id)
    WHERE supersedes_participation_id IS NOT NULL;

CREATE INDEX ix_poll_participations_poll_property ON public.poll_participations (poll_id, property_id, cast_at);

CREATE FUNCTION public.poll_participations_chain() RETURNS trigger
    LANGUAGE plpgsql AS $$
DECLARE
    v_prev record;
BEGIN
    IF NEW.supersedes_participation_id IS NULL THEN
        -- Primera participacion de la unidad en la pregunta; si ya hay alguna, solo se continua la cadena.
        IF EXISTS (SELECT 1 FROM public.poll_participations p
                   WHERE p.poll_id = NEW.poll_id AND p.property_id = NEW.property_id) THEN
            RAISE EXCEPTION 'poll_participations: la unidad ya participo; una nueva emision debe continuar la cadena'
                USING ERRCODE = '23514', CONSTRAINT = 'poll_participations_chain_required';
        END IF;
        RETURN NEW;
    END IF;
    SELECT p.status, p.representation_type, p.proxy_id INTO v_prev
        FROM public.poll_participations p WHERE p.id = NEW.supersedes_participation_id;
    IF v_prev.status IS DISTINCT FROM 'VOIDED'
       OR v_prev.representation_type IS DISTINCT FROM 'ADMIN_ASSISTED_PROXY'
       OR NEW.representation_type IS DISTINCT FROM 'ADMIN_ASSISTED_PROXY'
       OR v_prev.proxy_id IS DISTINCT FROM NEW.proxy_id THEN
        RAISE EXCEPTION 'poll_participations: solo un voto asistido anulado se sustituye, por otro voto asistido del mismo poder'
            USING ERRCODE = '23514', CONSTRAINT = 'poll_participations_reissue';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_poll_participations_chain
    BEFORE INSERT ON public.poll_participations
    FOR EACH ROW EXECUTE FUNCTION public.poll_participations_chain();

-- La referencia a la participacion sustituida no cambia nunca (el guard de V104 no conoce esta columna).
CREATE FUNCTION public.poll_participations_chain_immutable() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.supersedes_participation_id IS DISTINCT FROM OLD.supersedes_participation_id THEN
        RAISE EXCEPTION 'poll_participations: la cadena de nuevas emisiones no se modifica'
            USING ERRCODE = '23514', CONSTRAINT = 'poll_participations_chain_immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_poll_participations_chain_immutable
    BEFORE UPDATE ON public.poll_participations
    FOR EACH ROW EXECUTE FUNCTION public.poll_participations_chain_immutable();

-- Nueva accion de auditoria (las anteriores se conservan).
ALTER TABLE public.assembly_vote_events DROP CONSTRAINT assembly_vote_events_action_check;
ALTER TABLE public.assembly_vote_events ADD CONSTRAINT assembly_vote_events_action_check CHECK (action IN ('VOTE_CAST',
    'ASSISTED_VOTE_CAST', 'ASSISTED_VOTE_VOIDED', 'ASSISTED_VOTE_REISSUED', 'DUPLICATE_VOTE_REJECTED',
    'UNAUTHORIZED_VOTE_REJECTED', 'VOID_REJECTED', 'ASSEMBLY_TRANSITION_REJECTED'));
ALTER TABLE public.assembly_vote_events DROP CONSTRAINT assembly_vote_events_result_action_check;
ALTER TABLE public.assembly_vote_events ADD CONSTRAINT assembly_vote_events_result_action_check CHECK (
    (result = 'SUCCESS') = (action IN ('VOTE_CAST', 'ASSISTED_VOTE_CAST', 'ASSISTED_VOTE_VOIDED', 'ASSISTED_VOTE_REISSUED')));
