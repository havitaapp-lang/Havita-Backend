package com.havita.modules.identity.application;

import com.havita.modules.identity.domain.LegalConsent;
import com.havita.modules.identity.domain.LegalDocumentType;
import com.havita.modules.identity.infrastructure.LegalConsentRepository;
import com.havita.security.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class LegalConsentService {

    private final LegalConsentRepository legalConsentRepository;

    @Transactional
    public LegalConsent recordConsent(UUID userId, LegalDocumentType documentType, String documentVersion, String ipAddress, String userAgent) {
        return recordConsent(userId, null, documentType, documentVersion, ipAddress, userAgent);
    }

    @Transactional
    public LegalConsent recordConsent(UUID userId, UUID tenantId, LegalDocumentType documentType, String documentVersion, String ipAddress, String userAgent) {
        if (tenantId == null) {
            try {
                tenantId = TenantContextHolder.getTenantId();
            } catch (Exception ignored) {
                // Ignorar, el tenantId puede ser null en roles globales o si no se especificó un tenant en el contexto
            }
        }

        LegalConsent consent = LegalConsent.builder()
                .userId(userId)
                .tenantId(tenantId)
                .documentType(documentType)
                .documentVersion(documentVersion)
                .ipAddress(ipAddress)
                .userAgent(userAgent)
                .acceptedAt(OffsetDateTime.now())
                .build();

        return legalConsentRepository.save(consent);
    }

    @Transactional(readOnly = true)
    public boolean hasUserAcceptedVersion(UUID userId, LegalDocumentType documentType, String documentVersion) {
        return legalConsentRepository.existsByUserIdAndDocumentTypeAndDocumentVersion(userId, documentType, documentVersion);
    }
}
