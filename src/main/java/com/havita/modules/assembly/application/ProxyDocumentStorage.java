package com.havita.modules.assembly.application;

import java.io.InputStream;
import java.util.UUID;

/**
 * Fase 2 / P1: almacenamiento PRIVADO del documento de soporte de un poder. Las implementaciones nunca devuelven URLs
 * publicas; la lectura pasa siempre por el servicio de poderes, que autoriza antes de abrir el documento.
 *
 * La implementacion por defecto ({@link ObjectStorageProxyDocumentStorage}) usa {@code ObjectStorageService}. Con el
 * proveedor local guarda en disco del servidor; el adaptador R2 actual aun no esta implementado (lanza
 * UnsupportedOperationException), asi que con {@code storage.provider=r2} los poderes no pueden almacenarse: es un
 * bloqueo documentado para la activacion, no se resuelve en P1.
 */
public interface ProxyDocumentStorage {

    /**
     * Guarda el contenido ya validado y devuelve su referencia opaca. {@code operationId} (el id del poder) queda en el
     * nombre del objeto: permite conciliar un objeto huerfano con la operacion que lo subio (P5-C).
     */
    String store(UUID tenantId, UUID operationId, byte[] content, ProxyDocumentInspector.DetectedType type);

    /** Abre el documento de la referencia, que debe pertenecer a {@code tenantId}. */
    InputStream open(UUID tenantId, String documentKey);

    /** Elimina un documento que no llego a quedar referenciado (rollback de la operacion que lo subio). */
    void discard(String documentKey);

    /** P5-C: referencias guardadas de la comunidad (solo lectura; conciliacion). */
    java.util.List<String> listKeys(UUID tenantId);

    /** P5-C: ¿existe el objeto de la referencia? (conciliacion). */
    boolean exists(String documentKey);
}
