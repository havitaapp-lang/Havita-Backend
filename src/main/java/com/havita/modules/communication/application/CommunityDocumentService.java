package com.havita.modules.communication.application;

import com.havita.common.exception.ResourceNotFoundException;
import com.havita.modules.communication.api.dto.CommunityDocumentResponse;
import com.havita.modules.communication.api.dto.CreateDocumentRequest;
import com.havita.modules.communication.domain.CommunityDocument;
import com.havita.modules.communication.infrastructure.CommunityDocumentRepository;
import com.havita.modules.identity.domain.User;
import com.havita.modules.identity.infrastructure.UserRepository;
import com.havita.security.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.havita.modules.sync.application.SseService;
import com.havita.modules.sync.domain.SyncEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class CommunityDocumentService {

    private final CommunityDocumentRepository documentRepository;
    private final UserRepository userRepository;
    private final SseService sseService;

    @Transactional(readOnly = true)
    public Page<CommunityDocumentResponse> findAll(String search, String category, Pageable pageable) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        Page<CommunityDocument> page;

        if (search != null && !search.isBlank()) {
            page = documentRepository.searchDocuments(tenantId, search.trim(), pageable);
        } else if (category != null && !category.isBlank()) {
            page = documentRepository.findByTenantIdAndCategory(tenantId, category.trim(), pageable);
        } else {
            page = documentRepository.findByTenantId(tenantId, pageable);
        }

        return page.map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public CommunityDocumentResponse findById(UUID id) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        CommunityDocument doc = documentRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("CommunityDocument", id));
        return toResponse(doc);
    }

    @Transactional
    public CommunityDocumentResponse create(CreateDocumentRequest request, UUID uploaderUserId) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();

        User uploader = null;
        if (uploaderUserId != null) {
            uploader = userRepository.findById(uploaderUserId).orElse(null);
        }

        CommunityDocument doc = CommunityDocument.builder()
                .uploadedByUser(uploader)
                .title(request.title().trim())
                .category(request.category() != null ? request.category() : "General")
                .fileType(request.fileType() != null ? request.fileType() : "PDF")
                .fileSize(request.fileSize().trim())
                .fileUrl(request.storageKey() != null ? downloadUrlFor(request.storageKey(), tenantId) : request.fileUrl().trim())
                .storageKey(request.storageKey())
                .description(request.description())
                .downloadsCount(0)
                .build();
        doc.setTenantId(tenantId);

        doc = documentRepository.save(doc);
        log.info("Documento comunitario cargado: '{}' (Categoría: {})", doc.getTitle(), doc.getCategory());
        
        CommunityDocumentResponse response = toResponse(doc);
        sseService.publishEvent(new SyncEvent("DOCUMENT_CREATED", tenantId, Map.of("document", response)));
        return response;
    }

    /**
     * SEC-9-9: la clave la aporta el cliente; solo se acepta una clave de archivo de documentos de ESTA comunidad, y la
     * URL la fija el servidor (antes cualquier clave o URL quedaba asociada al documento y era legible por sus lectores).
     */
    private String downloadUrlFor(String storageKey, UUID tenantId) {
        com.havita.common.storage.StorageKey key = com.havita.common.storage.StorageKey.parse(storageKey)
                .filter(k -> k.tenantId().equals(tenantId) && "documents".equals(k.folder()) && k.subPath().isEmpty())
                .orElseThrow(() -> new com.havita.common.exception.BusinessRuleConflictException(
                        "INVALID_STORAGE_KEY", "El archivo no pertenece a los documentos de esta copropiedad."));
        return "/api/v1/files/download?key=" + key.value();
    }

    @Transactional
    public CommunityDocumentResponse recordDownload(UUID id) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        CommunityDocument doc = documentRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("CommunityDocument", id));

        doc.incrementDownloads();
        doc = documentRepository.save(doc);
        log.info("Descarga registrada para documento {}: total {}", id, doc.getDownloadsCount());
        return toResponse(doc);
    }

    @Transactional
    public CommunityDocumentResponse update(UUID id, com.havita.modules.communication.api.dto.UpdateDocumentRequest request) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        CommunityDocument doc = documentRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("CommunityDocument", id));

        if (request.title() != null) doc.setTitle(request.title().trim());
        if (request.category() != null) doc.setCategory(request.category());
        if (request.description() != null) doc.setDescription(request.description());
        if (request.fileSize() != null && !request.fileSize().isBlank()) doc.setFileSize(request.fileSize().trim());
        if (request.fileType() != null && !request.fileType().isBlank()) doc.setFileType(request.fileType());
        if (request.fileUrl() != null && !request.fileUrl().isBlank()) doc.setFileUrl(request.fileUrl().trim());
        if (request.storageKey() != null) {
            doc.setFileUrl(downloadUrlFor(request.storageKey(), tenantId));
            doc.setStorageKey(request.storageKey());
        }

        doc = documentRepository.save(doc);
        return toResponse(doc);
    }

    @Transactional
    public void delete(UUID id) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        CommunityDocument doc = documentRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("CommunityDocument", id));
        documentRepository.delete(doc);
    }

    private CommunityDocumentResponse toResponse(CommunityDocument d) {
        return new CommunityDocumentResponse(
                d.getId(),
                d.getTenantId(),
                d.getUploadedByUser() != null ? d.getUploadedByUser().getId() : null,
                d.getTitle(),
                d.getCategory(),
                d.getFileType(),
                d.getFileSize(),
                d.getFileUrl(),
                d.getStorageKey(),
                d.getDescription(),
                d.getDownloadsCount(),
                d.getCreatedAt(),
                d.getUpdatedAt()
        );
    }
}
