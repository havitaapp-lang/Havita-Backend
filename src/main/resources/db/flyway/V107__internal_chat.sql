-- Fase 3 — Chat interno residente ↔ porteria (solo texto).
--
-- Migracion incremental posterior a V106. NO modifica V100-V106 ni datos existentes. Crea tres tablas nuevas, con las
-- mismas columnas que las entidades JPA del modulo chat (ChatConversation, ChatConversationMessage,
-- ChatConversationEvent): la aplicacion sigue creando su esquema con Hibernate (ddl-auto=update) y, en el modo Flyway,
-- Hibernate solo lo valida.
--
-- La tabla anterior concierge_chat_messages (chat por unidad sin conversaciones, sin uso en el frontend) NO se toca.

-- Un hilo por (comunidad, unidad, residente). Invariante: status = 'ACTIVE' <=> assigned_to_user_id no nulo.
CREATE TABLE public.chat_conversations (
    id                         uuid                        NOT NULL,
    tenant_id                  uuid                        NOT NULL,
    property_id                uuid                        NOT NULL,
    resident_user_id           uuid                        NOT NULL,
    status                     character varying(20)       NOT NULL,
    assigned_to_user_id        uuid,
    assigned_at                timestamp(6) with time zone,
    last_message_seq           bigint                      NOT NULL DEFAULT 0,
    last_message_at            timestamp(6) with time zone,
    last_message_preview       character varying(160),
    last_message_author_role   character varying(20),
    resident_unread            integer                     NOT NULL DEFAULT 0,
    staff_unread               integer                     NOT NULL DEFAULT 0,
    resident_last_read_seq     bigint                      NOT NULL DEFAULT 0,
    staff_last_read_seq        bigint                      NOT NULL DEFAULT 0,
    closed_at                  timestamp(6) with time zone,
    version                    bigint                      NOT NULL DEFAULT 0,
    created_at                 timestamp(6) with time zone NOT NULL,
    updated_at                 timestamp(6) with time zone NOT NULL,
    CONSTRAINT chat_conversations_pkey PRIMARY KEY (id),
    CONSTRAINT uk_chat_conversations_resident_unit UNIQUE (tenant_id, property_id, resident_user_id),
    CONSTRAINT fk_chat_conversations_tenant FOREIGN KEY (tenant_id) REFERENCES public.tenants (id),
    CONSTRAINT fk_chat_conversations_property FOREIGN KEY (property_id) REFERENCES public.properties (id),
    CONSTRAINT fk_chat_conversations_resident FOREIGN KEY (resident_user_id) REFERENCES public.users (id),
    CONSTRAINT fk_chat_conversations_assignee FOREIGN KEY (assigned_to_user_id) REFERENCES public.users (id),
    CONSTRAINT chat_conversations_status_check CHECK (status IN ('PENDING', 'ACTIVE', 'CLOSED')),
    CONSTRAINT chat_conversations_assignment_check CHECK ((status = 'ACTIVE') = (assigned_to_user_id IS NOT NULL)),
    CONSTRAINT chat_conversations_author_role_check
        CHECK (last_message_author_role IS NULL OR last_message_author_role IN ('RESIDENT', 'STAFF')),
    CONSTRAINT chat_conversations_counters_check
        CHECK (resident_unread >= 0 AND staff_unread >= 0 AND last_message_seq >= 0
               AND resident_last_read_seq BETWEEN 0 AND last_message_seq
               AND staff_last_read_seq BETWEEN 0 AND last_message_seq)
);

-- Bandeja de porteria (comunidad + estado, por actividad), responsable y conversaciones del residente.
CREATE INDEX ix_chat_conversations_inbox ON public.chat_conversations (tenant_id, status, last_message_at);
CREATE INDEX ix_chat_conversations_assignee ON public.chat_conversations (tenant_id, assigned_to_user_id);
CREATE INDEX ix_chat_conversations_resident ON public.chat_conversations (tenant_id, resident_user_id);

-- Mensajes: orden sin huecos por conversacion (seq) e idempotencia por id de cliente.
CREATE TABLE public.chat_messages (
    id                 uuid                        NOT NULL,
    tenant_id          uuid                        NOT NULL,
    conversation_id    uuid                        NOT NULL,
    seq                bigint                      NOT NULL,
    author_user_id     uuid                        NOT NULL,
    author_role        character varying(20)       NOT NULL,
    author_name        character varying(150)      NOT NULL,
    body               character varying(1000)     NOT NULL,
    client_message_id  uuid,
    created_at         timestamp(6) with time zone NOT NULL,
    updated_at         timestamp(6) with time zone NOT NULL,
    CONSTRAINT chat_messages_pkey PRIMARY KEY (id),
    CONSTRAINT uk_chat_messages_seq UNIQUE (conversation_id, seq),
    CONSTRAINT uk_chat_messages_client_id UNIQUE (conversation_id, author_user_id, client_message_id),
    CONSTRAINT fk_chat_messages_tenant FOREIGN KEY (tenant_id) REFERENCES public.tenants (id),
    CONSTRAINT fk_chat_messages_conversation FOREIGN KEY (conversation_id) REFERENCES public.chat_conversations (id),
    CONSTRAINT fk_chat_messages_author FOREIGN KEY (author_user_id) REFERENCES public.users (id),
    CONSTRAINT chat_messages_author_role_check CHECK (author_role IN ('RESIDENT', 'STAFF')),
    CONSTRAINT chat_messages_body_check CHECK (char_length(btrim(body)) > 0),
    CONSTRAINT chat_messages_seq_check CHECK (seq > 0)
);

-- Auditoria (solo insercion): tomar, liberar, transferir, liberacion automatica, cerrar, reabrir.
CREATE TABLE public.chat_conversation_events (
    id               uuid                        NOT NULL,
    tenant_id        uuid                        NOT NULL,
    conversation_id  uuid                        NOT NULL,
    type             character varying(30)       NOT NULL,
    actor_user_id    uuid,
    from_user_id     uuid,
    to_user_id       uuid,
    created_at       timestamp(6) with time zone NOT NULL,
    updated_at       timestamp(6) with time zone NOT NULL,
    CONSTRAINT chat_conversation_events_pkey PRIMARY KEY (id),
    CONSTRAINT fk_chat_conversation_events_tenant FOREIGN KEY (tenant_id) REFERENCES public.tenants (id),
    CONSTRAINT fk_chat_conversation_events_conversation FOREIGN KEY (conversation_id)
        REFERENCES public.chat_conversations (id),
    CONSTRAINT fk_chat_conversation_events_actor FOREIGN KEY (actor_user_id) REFERENCES public.users (id),
    CONSTRAINT fk_chat_conversation_events_from FOREIGN KEY (from_user_id) REFERENCES public.users (id),
    CONSTRAINT fk_chat_conversation_events_to FOREIGN KEY (to_user_id) REFERENCES public.users (id),
    CONSTRAINT chat_conversation_events_type_check CHECK (type IN
        ('CREATED', 'CLAIMED', 'RELEASED', 'TRANSFERRED', 'AUTO_RELEASED', 'CLOSED', 'REOPENED'))
);

CREATE INDEX ix_chat_conversation_events_conversation ON public.chat_conversation_events (conversation_id, created_at);

CREATE FUNCTION public.chat_conversation_events_append_only() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'chat_conversation_events: historial de solo insercion'
        USING ERRCODE = '23514', CONSTRAINT = 'chat_conversation_events_append_only';
END;
$$;

CREATE TRIGGER trg_chat_conversation_events_append_only
    BEFORE UPDATE OR DELETE ON public.chat_conversation_events
    FOR EACH ROW EXECUTE FUNCTION public.chat_conversation_events_append_only();
