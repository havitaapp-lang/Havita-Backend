package com.havita.modules.identity.infrastructure;

import com.havita.modules.identity.domain.LegalConsent;
import com.havita.modules.identity.domain.LegalDocumentType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface LegalConsentRepository extends JpaRepository<LegalConsent, UUID> {
    Optional<LegalConsent> findFirstByUserIdAndDocumentTypeAndDocumentVersionOrderByAcceptedAtDesc(
            UUID userId, LegalDocumentType documentType, String documentVersion);

    boolean existsByUserIdAndDocumentTypeAndDocumentVersion(UUID userId, LegalDocumentType documentType, String documentVersion);
}
