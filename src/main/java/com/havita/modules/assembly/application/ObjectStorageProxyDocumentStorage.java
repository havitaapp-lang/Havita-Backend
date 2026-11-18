package com.havita.modules.assembly.application;

import com.havita.common.exception.ResourceNotFoundException;
import com.havita.common.storage.ObjectStorageService;
import com.havita.common.storage.StorageKey;
import com.havita.common.storage.StoredFile;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.UUID;

/**
 * Fase 2 / P1: documentos de poderes sobre {@link ObjectStorageService}, en la carpeta privada {@code assembly-proxies}.
 * Esa carpeta no tiene {@code StoredFileAccessRule}, asi que el controlador generico de archivos no la sirve ("una
 * carpeta sin regla no se sirve"): solo el servicio de poderes la lee, despues de autorizar. Nombre de archivo
 * generico (sin el nombre original, que puede contener datos personales) y referencia opaca generada por el servidor.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "havita.assembly-proxies.enabled", havingValue = "true")
public class ObjectStorageProxyDocumentStorage implements ProxyDocumentStorage {

    static final String FOLDER = "assembly-proxies";

    private final ObjectStorageService storage;

    public ObjectStorageProxyDocumentStorage(ObjectStorageService storage) {
        this.storage = storage;
    }

    @Override
    public String store(UUID tenantId, UUID operationId, byte[] content, ProxyDocumentInspector.DetectedType type) {
        StoredFile file = storage.upload(tenantId, FOLDER, "poder-" + operationId + "." + type.extension, type.contentType,
                content, false);
        return file.storageKey();
    }

    @Override
    public InputStream open(UUID tenantId, String documentKey) {
        StorageKey key = StorageKey.parse(documentKey)
                .filter(k -> k.tenantId().equals(tenantId) && FOLDER.equals(k.folder()))
                .orElseThrow(() -> new ResourceNotFoundException("ProxyDocument", tenantId));
        return storage.get(key.value()).inputStream();
    }

    @Override
    public void discard(String documentKey) {
        try {
            storage.delete(documentKey);
        } catch (RuntimeException e) {
            // P5-C: queda un objeto HUERFANO (no referenciado). No se reintenta aqui; lo detecta la conciliacion
            // (ProxyDocumentReconciler), que nunca borra por si sola.
            log.warn("No se pudo eliminar un documento de poder no referenciado ({}); queda para conciliacion",
                    e.getClass().getSimpleName());
        }
    }

    @Override
    public java.util.List<String> listKeys(UUID tenantId) {
        return storage.listKeys(tenantId, FOLDER);
    }

    @Override
    public boolean exists(String documentKey) {
        return storage.exists(documentKey);
    }
}
