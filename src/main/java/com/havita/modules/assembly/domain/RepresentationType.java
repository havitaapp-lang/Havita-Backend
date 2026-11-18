package com.havita.modules.assembly.domain;

/**
 * Fase 2 / P4 — titulo con el que una unidad participa en una pregunta. Lo deriva SIEMPRE el servidor de la
 * autorizacion real (asistencia, relacion de residente, delegacion o poder); nunca se acepta del cliente.
 */
public enum RepresentationType {
    /** Unico propietario activo de la unidad, representante registrado en la asistencia. */
    OWNER,
    /** Uno de varios propietarios activos, el representante registrado en la asistencia. */
    CO_OWNER,
    /** Arrendatario con delegacion ACTIVA a su favor, representante registrado en la asistencia. */
    TENANT_DELEGATE,
    /** Apoderado con cuenta Havita y poder USED: vota con su propia sesion. */
    ACCOUNT_PROXY,
    /** Apoderado externo (sin cuenta) presente: la administracion registra el voto que el expresa en persona. */
    ADMIN_ASSISTED_PROXY
}
