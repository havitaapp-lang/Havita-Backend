package com.havita.modules.chat.domain;

/**
 * Fase 3 — estado de una conversacion residente ↔ porteria.
 *
 * <ul>
 *   <li>{@code PENDING}: sin responsable; visible para toda la porteria como pendiente de atencion.</li>
 *   <li>{@code ACTIVE}: con un unico portero responsable ({@code assignedTo} no nulo).</li>
 *   <li>{@code CLOSED}: finalizada, sin responsable. Un mensaje nuevo la reabre.</li>
 * </ul>
 * Invariante: {@code ACTIVE} ⇔ responsable no nulo.
 */
public enum ChatConversationStatus {
    PENDING, ACTIVE, CLOSED
}
