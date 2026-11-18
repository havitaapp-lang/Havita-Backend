package com.havita.modules.communication.infrastructure;

import com.havita.modules.communication.domain.CommunityDocument;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CommunityDocumentRepository extends JpaRepository<CommunityDocument, UUID> {

    List<CommunityDocument> findByTenantId(UUID tenantId);

    Page<CommunityDocument> findByTenantId(UUID tenantId, Pageable pageable);

    Optional<CommunityDocument> findByIdAndTenantId(UUID id, UUID tenantId);

    Page<CommunityDocument> findByTenantIdAndCategory(UUID tenantId, String category, Pageable pageable);

    @Query("SELECT d FROM CommunityDocument d WHERE d.tenantId = :tenantId AND (" +
           "LOWER(d.title) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
           "LOWER(d.description) LIKE LOWER(CONCAT('%', :search, '%')))")
    Page<CommunityDocument> searchDocuments(UUID tenantId, String search, Pageable pageable);

    long countByTenantId(UUID tenantId);

    /** SEC-9-9: el archivo pertenece a un documento de esa comunidad. */
    boolean existsByTenantIdAndStorageKey(UUID tenantId, String storageKey);
}
