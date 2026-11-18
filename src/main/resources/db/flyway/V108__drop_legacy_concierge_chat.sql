-- Fase 3 — Retirada de la tabla del chat ANTERIOR por unidad (concierge_chat_messages).
--
-- El chat anterior (/api/v1/realtime/chat/properties/{propertyId}, entidad realtime.domain.ChatMessage) se retiro del
-- codigo en la Fase 3; lo sustituye el chat interno de V107 (chat_conversations, chat_messages,
-- chat_conversation_events). La tabla ya no la usa nada.
--
-- Salvaguarda para bases con historia desconocida (p. ej. produccion): SOLO se elimina si esta VACIA. Si tiene filas,
-- la migracion FALLA sin cambiar nada (Flyway revierte la transaccion) para que se decida que hacer con esos mensajes
-- (exportarlos o descartarlos) antes de repetirla. No es destructiva sobre datos.
DO $$
DECLARE
    filas bigint;
BEGIN
    IF to_regclass('public.concierge_chat_messages') IS NOT NULL THEN
        EXECUTE 'SELECT count(*) FROM public.concierge_chat_messages' INTO filas;
        IF filas > 0 THEN
            RAISE EXCEPTION 'V108: concierge_chat_messages tiene % fila(s); no se elimina. Decidir antes que hacer con esos mensajes.', filas
                USING ERRCODE = 'P0001';
        END IF;
        EXECUTE 'DROP TABLE public.concierge_chat_messages';
    END IF;
END $$;
