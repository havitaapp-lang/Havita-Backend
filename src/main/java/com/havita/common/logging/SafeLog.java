package com.havita.common.logging;

/**
 * SEC-9 (P2, SEC-9-8): ayudas para escribir en los logs sin datos personales ni secretos.
 *
 * Los logs no son un canal seguro (se copian, se exportan y los leen mas personas que la base de datos). Para la
 * trazabilidad basta con identificadores internos (ids) y el traceId de la peticion (CorrelationIdFilter); un correo
 * solo aparece enmascarado ({@code a***@dominio}), suficiente para distinguir destinatarios sin exponerlos.
 */
public final class SafeLog {

    private SafeLog() {
    }

    /** {@code ana.perez@example.test -> a***@example.test}; valores vacios o sin arroba -> {@code ***}. */
    public static String maskEmail(String email) {
        if (email == null || email.isBlank()) {
            return "***";
        }
        String e = email.trim();
        int at = e.lastIndexOf('@');
        if (at <= 0 || at == e.length() - 1) {
            return "***";
        }
        return e.charAt(0) + "***@" + e.substring(at + 1);
    }

    /**
     * Describe un error sin su mensaje: los mensajes de clientes HTTP y de librerias pueden incluir cuerpos de
     * respuesta, cabeceras o los datos enviados. Se registra solo el tipo de la excepcion.
     */
    public static String errorType(Throwable error) {
        return error == null ? "-" : error.getClass().getSimpleName();
    }
}
