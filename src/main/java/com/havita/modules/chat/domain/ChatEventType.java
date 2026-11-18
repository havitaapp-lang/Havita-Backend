package com.havita.modules.chat.domain;

/** Auditoria de la conversacion (chat_conversation_events). */
public enum ChatEventType {
    /** Conversacion creada (por el residente o por un portero). */
    CREATED,
    /** Un portero toma la conversacion. */
    CLAIMED,
    /** El responsable la libera (vuelve a PENDING). */
    RELEASED,
    /** El responsable la transfiere a otro portero. */
    TRANSFERRED,
    /** Liberada por el sistema: el responsable perdio el acceso de porteria. */
    AUTO_RELEASED,
    CLOSED,
    /** Un mensaje nuevo reabre una conversacion cerrada. */
    REOPENED
}
