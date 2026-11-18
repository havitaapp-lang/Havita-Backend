package com.havita.modules.communication.application;

import com.havita.common.storage.StorageKey;
import com.havita.common.storage.StoredFileAccessRule;
import com.havita.modules.communication.infrastructure.CommunityDocumentRepository;
import com.havita.security.EffectiveAccess;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/**
 * SEC-9-9: archivos de {@code documents/} (repositorio de documentos de la comunidad).
 * <ul>
 *   <li>Archivo de un documento de la comunidad: lo leen los mismos roles que leen los documentos
 *       ({@code GET /communications/documents}).</li>
 *   <li>Archivo subido pero aun no asociado a ningun documento: solo la administracion de la comunidad (lo acaba de
 *       subir para crear el documento).</li>
 * </ul>
 * Solo sube la administracion, como en {@code AdminDocuments}.
 */
@Component
@RequiredArgsConstructor
public class CommunityDocumentFileRule implements StoredFileAccessRule {

    static final Set<String> READERS = Set.of("SUPER_ADMIN", "COMMUNITY_ADMIN", "SECURITY", "STAFF", "RESIDENT");
    static final Set<String> ADMINS = Set.of("SUPER_ADMIN", "COMMUNITY_ADMIN");

    private final CommunityDocumentRepository documentRepository;

    @Override
    public String folder() {
        return "documents";
    }

    @Override
    public Set<String> uploaderRoles() {
        return ADMINS;
    }

    @Override
    public Set<String> allowedExtensions() {
        return Set.of("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "csv", "txt", "png", "jpg", "jpeg", "webp");
    }

    @Override
    @Transactional(readOnly = true)
    public boolean canRead(StorageKey key, EffectiveAccess access) {
        String role = access.role().name();
        if (!READERS.contains(role) || !key.subPath().isEmpty()) {
            return false;
        }
        if (documentRepository.existsByTenantIdAndStorageKey(key.tenantId(), key.value())) {
            return true;
        }
        return ADMINS.contains(role);
    }
}
