package com.havita.modules.marketplace.application;

import com.havita.common.api.PageResponse;
import com.havita.common.exception.BusinessRuleConflictException;
import com.havita.common.exception.InvalidRequestException;
import java.math.BigDecimal;
import com.havita.common.exception.ResourceNotFoundException;
import com.havita.common.storage.ObjectStorageService;
import com.havita.modules.identity.domain.User;
import com.havita.modules.identity.infrastructure.UserRepository;
import com.havita.modules.marketplace.api.dto.*;
import com.havita.modules.marketplace.domain.*;
import com.havita.modules.marketplace.infrastructure.*;
import com.havita.modules.resident.domain.Resident;
import com.havita.modules.resident.domain.ResidentStatus;
import com.havita.modules.resident.infrastructure.ResidentRepository;
import com.havita.modules.sync.application.SseService;
import com.havita.modules.sync.domain.SyncEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class MarketplaceListingService {

    private final MarketplaceListingRepository listingRepository;
    private final MarketplaceReportRepository reportRepository;
    private final MarketplaceConfigService configService;
    private final ObjectStorageService storageService;
    private final UserRepository userRepository;
    private final ResidentRepository residentRepository;
    private final SseService sseService;
    private final com.havita.common.storage.FileUrlSigner fileUrlSigner;

    // ─── RESIDENT: Browse ───────────────────────────────────────────────

    @Transactional(readOnly = true)
    public PageResponse<MarketplaceListingResponse> getPublishedListings(
            UUID tenantId, String type, String search, Pageable pageable) {
        ListingType listingType = parseType(type);
        boolean hasSearch = search != null && !search.isBlank();
        Page<MarketplaceListing> page;

        if (listingType != null && hasSearch) {
            page = listingRepository.findByTenantIdAndStatusAndTypeAndSearch(
                    tenantId, ListingStatus.PUBLISHED, listingType, search.trim(), pageable);
        } else if (listingType != null) {
            page = listingRepository.findByTenantIdAndStatusAndType(
                    tenantId, ListingStatus.PUBLISHED, listingType, pageable);
        } else if (hasSearch) {
            page = listingRepository.findByTenantIdAndStatusAndSearch(
                    tenantId, ListingStatus.PUBLISHED, search.trim(), pageable);
        } else {
            page = listingRepository.findByTenantIdAndStatus(
                    tenantId, ListingStatus.PUBLISHED, pageable);
        }
        return PageResponse.from(page.map(this::toResponse));
    }

    @Transactional(readOnly = true)
    public MarketplaceListingResponse getListingById(UUID tenantId, UUID id) {
        MarketplaceListing listing = listingRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("MarketplaceListing", id));
        if (listing.getStatus() != ListingStatus.PUBLISHED) {
            throw new ResourceNotFoundException("MarketplaceListing", id);
        }
        return toResponse(listing);
    }

    // ─── RESIDENT: My Listings ──────────────────────────────────────────────

    @Transactional(readOnly = true)
    public PageResponse<MarketplaceListingResponse> getMyListings(UUID tenantId, UUID userId, Pageable pageable) {
        Page<MarketplaceListing> page = listingRepository.findByTenantIdAndCreatedByUser_Id(tenantId, userId, pageable);
        return PageResponse.from(page.map(this::toResponse));
    }

    // ─── RESIDENT: Create ────────────────────────────────────────────────

    @Transactional
    public MarketplaceListingResponse createListing(UUID tenantId, UUID userId, CreateListingRequest request) {
        validatePrice(request.priceType(), request.price());
        String contactPhone = normalizePhone(request.contactPhone());
        if (request.imageKeys() != null && request.imageKeys().size() > 5) {
            throw new BusinessRuleConflictException("TOO_MANY_IMAGES", "El máximo de imágenes por publicación es 5.");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));

        MarketplaceMode mode = configService.getMode(tenantId);
        int durationDays = configService.getListingDurationDays(tenantId);
        ListingStatus initialStatus = (mode == MarketplaceMode.AUTO_APPROVE)
                ? ListingStatus.PUBLISHED : ListingStatus.PENDING_APPROVAL;

        if (request.termsAccepted() == null || !request.termsAccepted()) {
            throw new BusinessRuleConflictException("TERMS_NOT_ACCEPTED", "Debe aceptar los términos y condiciones.");
        }

        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime publishedAt = (initialStatus == ListingStatus.PUBLISHED) ? now : null;
        OffsetDateTime expiresAt = (initialStatus == ListingStatus.PUBLISHED) ? now.plusDays(durationDays) : null;

        String propertyUnit = residentRepository.findByUserIdAndTenantId(userId, tenantId).stream()
                .filter(r -> r.getStatus() == ResidentStatus.ACTIVE)
                .map(r -> r.getProperty() != null ? r.getProperty().getPath() : null)
                .filter(path -> path != null && !path.isBlank())
                .findFirst()
                .orElse(null);

        MarketplaceListing listing = new MarketplaceListing();
        listing.setTenantId(tenantId);
        listing.setCreatedByUser(user);
        listing.setPropertyUnit(propertyUnit);
        listing.setType(request.type());
        listing.setCategory(requireText(request.category(), "La categoría"));
        listing.setTitle(requireText(request.title(), "El título"));
        listing.setDescription(requireText(request.description(), "La descripción"));
        listing.setPrice(request.priceType() == PriceType.CONTACT ? null : request.price());
        listing.setPriceType(request.priceType());
        listing.setStatus(initialStatus);
        listing.setContactPhone(contactPhone);
        listing.setPublishedAt(publishedAt);
        listing.setExpiresAt(expiresAt);
        listing.setTermsAccepted(request.termsAccepted());
        if (request.imageKeys() != null && !request.imageKeys().isEmpty()) {
            listing.setImageKeysList(requireOwnImages(request.imageKeys(), tenantId, userId));
        }

        MarketplaceListing saved = listingRepository.save(listing);
        log.info("Marketplace listing created: {} by user {} in tenant {} — status: {}",
                saved.getId(), userId, tenantId, initialStatus);
        return toResponse(saved);
    }

    // ─── RESIDENT: Update ────────────────────────────────────────────────

    @Transactional
    public MarketplaceListingResponse updateListing(UUID tenantId, UUID userId, UUID id, UpdateListingRequest request) {
        MarketplaceListing listing = findOwnedListing(tenantId, userId, id);
        if (listing.getStatus() == ListingStatus.EXPIRED) {
            throw new BusinessRuleConflictException("LISTING_EXPIRED", "No se puede editar una publicación expirada.");
        }
        // Mismas reglas que al crear: un campo enviado no puede quedar vacio ni con formato invalido.
        if (request.category() != null) listing.setCategory(requireText(request.category(), "La categoría"));
        if (request.title() != null) listing.setTitle(requireText(request.title(), "El título"));
        if (request.description() != null) listing.setDescription(requireText(request.description(), "La descripción"));
        PriceType priceType = request.priceType() != null ? request.priceType() : listing.getPriceType();
        BigDecimal price = request.priceType() != null || request.price() != null ? request.price() : listing.getPrice();
        validatePrice(priceType, price);
        listing.setPriceType(priceType);
        listing.setPrice(priceType == PriceType.CONTACT ? null : price);
        if (request.imageKeys() != null) {
            if (request.imageKeys().size() > 5)
                throw new BusinessRuleConflictException("TOO_MANY_IMAGES", "El máximo de imágenes por publicación es 5.");
            listing.setImageKeysList(requireOwnImages(request.imageKeys(), tenantId, userId));
        }
        if (request.contactPhone() != null) listing.setContactPhone(normalizePhone(request.contactPhone()));
        return toResponse(listingRepository.save(listing));
    }

    // ─── Validacion comun (crear y editar) ──────────────────────────────

    /** Telefono: 7 a 20 caracteres, solo digitos, espacios, guiones y + inicial (misma regla que el frontend). */
    static final java.util.regex.Pattern PHONE = java.util.regex.Pattern.compile("^\\+?[0-9 -]{7,20}$");

    static String normalizePhone(String raw) {
        String phone = raw == null ? "" : raw.trim().replaceAll("\\s+", " ");
        if (!PHONE.matcher(phone).matches()) {
            throw new InvalidRequestException("INVALID_PHONE",
                    "El teléfono debe tener entre 7 y 20 caracteres: solo dígitos, espacios, guiones y + inicial.");
        }
        return phone;
    }

    static String requireText(String raw, String field) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            throw new InvalidRequestException("REQUIRED_FIELD", field + " no puede quedar vacío.");
        }
        return value;
    }

    static void validatePrice(PriceType priceType, BigDecimal price) {
        if (priceType != PriceType.CONTACT && price == null) {
            throw new BusinessRuleConflictException("PRICE_REQUIRED",
                    "El precio es obligatorio cuando el tipo de precio no es 'Precio por consultar'.");
        }
        if (price != null && price.signum() < 0) {
            throw new InvalidRequestException("INVALID_PRICE", "El precio no puede ser negativo.");
        }
    }

    // ─── RESIDENT: Delete & Visibility ─────────────────────────────────

    @Transactional
    public void deleteListing(UUID tenantId, UUID userId, UUID id) {
        MarketplaceListing listing = findOwnedListing(tenantId, userId, id);
        listing.setStatus(ListingStatus.HIDDEN);
        listing.setHiddenByAdmin(false);
        listing.setHiddenReason(null);
        listing.setHiddenAt(OffsetDateTime.now());
        listingRepository.save(listing);
        log.info("Listing {} hidden by owner {} in tenant {}", id, userId, tenantId);
    }

    @Transactional
    public MarketplaceListingResponse unhideListing(UUID tenantId, UUID userId, UUID id) {
        MarketplaceListing listing = findOwnedListing(tenantId, userId, id);
        if (listing.getStatus() != ListingStatus.HIDDEN) {
            throw new BusinessRuleConflictException("NOT_HIDDEN", "La publicación no se encuentra oculta.");
        }
        if (listing.isHiddenByAdmin()) {
            throw new BusinessRuleConflictException(
                    "HIDDEN_BY_ADMIN",
                    "Esta publicación fue suspendida por la administración por infringir las normas y no puede ser reactivada por el residente."
            );
        }
        OffsetDateTime now = OffsetDateTime.now();
        int durationDays = configService.getListingDurationDays(tenantId);

        listing.setStatus(ListingStatus.PUBLISHED);
        listing.setHiddenByAdmin(false);
        listing.setHiddenReason(null);
        listing.setHiddenAt(null);

        if (listing.getExpiresAt() == null || listing.getExpiresAt().isBefore(now)) {
            listing.setPublishedAt(now);
            listing.setExpiresAt(now.plusDays(durationDays));
        }

        MarketplaceListing saved = listingRepository.save(listing);
        log.info("Listing {} unhidden by owner {} in tenant {}", id, userId, tenantId);
        return toResponse(saved);
    }

    @Transactional
    public void purgeListing(UUID tenantId, UUID userId, UUID id) {
        MarketplaceListing listing = findOwnedListing(tenantId, userId, id);
        listingRepository.delete(listing);
        log.info("Listing {} permanently deleted by owner {} in tenant {}", id, userId, tenantId);
    }

    // ─── RESIDENT: Report ────────────────────────────────────────────────

    @Transactional
    public void reportListing(UUID tenantId, UUID userId, UUID id, ReportListingRequest request) {
        MarketplaceListing listing = listingRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("MarketplaceListing", id));
        if (listing.getStatus() != ListingStatus.PUBLISHED) {
            throw new BusinessRuleConflictException("LISTING_NOT_AVAILABLE", "Esta publicación no está disponible.");
        }
        if (listing.getCreatedByUser().getId().equals(userId)) {
            throw new BusinessRuleConflictException("SELF_REPORT", "No puedes reportar tu propia publicación.");
        }
        if (reportRepository.existsByListing_IdAndReportedByUserId(id, userId)) {
            throw new BusinessRuleConflictException("ALREADY_REPORTED", "Ya reportaste esta publicación anteriormente.");
        }
        MarketplaceReport report = new MarketplaceReport();
        report.setTenantId(tenantId);
        report.setListing(listing);
        report.setReportedByUserId(userId);
        report.setReason(request.reason());
        report.setDescription(request.description());
        report.setStatus(ReportStatus.OPEN);
        report = reportRepository.save(report);
        sseService.publishEvent(new SyncEvent("MARKETPLACE_REPORT_CREATED", tenantId, null));
        log.info("Listing {} reported by user {} — reason: {}", id, userId, request.reason());
    }

    // ─── ADMIN ────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public PageResponse<MarketplaceListingResponse> getPendingListings(UUID tenantId, Pageable pageable) {
        return PageResponse.from(
                listingRepository.findByTenantIdAndStatus(tenantId, ListingStatus.PENDING_APPROVAL, pageable)
                        .map(this::toResponse));
    }

    @Transactional(readOnly = true)
    public PageResponse<MarketplaceListingResponse> getAllListings(UUID tenantId, ListingStatus status, Pageable pageable) {
        Page<MarketplaceListing> page = (status != null)
                ? listingRepository.findByTenantIdAndStatus(tenantId, status, pageable)
                : listingRepository.findByTenantId(tenantId, pageable);
        return PageResponse.from(page.map(this::toResponse));
    }

    @Transactional
    public MarketplaceListingResponse approveListing(UUID tenantId, UUID adminId, UUID id) {
        MarketplaceListing listing = listingRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("MarketplaceListing", id));
        if (listing.getStatus() != ListingStatus.PENDING_APPROVAL) {
            throw new BusinessRuleConflictException("NOT_PENDING", "Solo se pueden aprobar publicaciones pendientes de revisión.");
        }
        OffsetDateTime now = OffsetDateTime.now();
        listing.setStatus(ListingStatus.PUBLISHED);
        listing.setPublishedAt(now);
        listing.setExpiresAt(now.plusDays(configService.getListingDurationDays(tenantId)));
        listing.setRejectionReason(null);
        log.info("Listing {} approved by admin {} in tenant {}", id, adminId, tenantId);
        return toResponse(listingRepository.save(listing));
    }

    @Transactional
    public MarketplaceListingResponse rejectListing(UUID tenantId, UUID adminId, UUID id, RejectListingRequest request) {
        MarketplaceListing listing = listingRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("MarketplaceListing", id));
        if (listing.getStatus() != ListingStatus.PENDING_APPROVAL) {
            throw new BusinessRuleConflictException("NOT_PENDING", "Solo se pueden rechazar publicaciones pendientes de revisión.");
        }
        listing.setStatus(ListingStatus.REJECTED);
        listing.setRejectionReason(request.reason());
        log.info("Listing {} rejected by admin {} — reason: {}", id, adminId, request.reason());
        return toResponse(listingRepository.save(listing));
    }

    @Transactional
    public MarketplaceListingResponse hideListing(UUID tenantId, UUID adminId, UUID id, HideListingRequest request) {
        MarketplaceListing listing = listingRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("MarketplaceListing", id));
        listing.setStatus(ListingStatus.HIDDEN);
        listing.setHiddenByAdmin(true);
        listing.setHiddenReason(request != null && request.reason() != null && !request.reason().isBlank()
                ? request.reason().trim() : "Incumplimiento de las normas comunitarias");
        listing.setHiddenAt(OffsetDateTime.now());
        log.info("Listing {} hidden by admin {} in tenant {} — reason: {}", id, adminId, tenantId, listing.getHiddenReason());
        MarketplaceListingResponse response = toResponse(listingRepository.save(listing));
        sseService.publishEvent(new SyncEvent("MARKETPLACE_REPORT_RESOLVED", tenantId, null));
        return response;
    }

    @Transactional
    public MarketplaceListingResponse hideListing(UUID tenantId, UUID adminId, UUID id) {
        return hideListing(tenantId, adminId, id, null);
    }

    @Transactional
    public MarketplaceListingResponse adminUnhideListing(UUID tenantId, UUID adminId, UUID id) {
        MarketplaceListing listing = listingRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("MarketplaceListing", id));
        if (listing.getStatus() != ListingStatus.HIDDEN) {
            throw new BusinessRuleConflictException("NOT_HIDDEN", "La publicación no se encuentra oculta.");
        }
        OffsetDateTime now = OffsetDateTime.now();
        int durationDays = configService.getListingDurationDays(tenantId);

        listing.setStatus(ListingStatus.PUBLISHED);
        listing.setHiddenByAdmin(false);
        listing.setHiddenReason(null);
        listing.setHiddenAt(null);

        if (listing.getExpiresAt() == null || listing.getExpiresAt().isBefore(now)) {
            listing.setPublishedAt(now);
            listing.setExpiresAt(now.plusDays(durationDays));
        }

        log.info("Listing {} unhidden by admin {} in tenant {}", id, adminId, tenantId);
        MarketplaceListingResponse response = toResponse(listingRepository.save(listing));
        sseService.publishEvent(new SyncEvent("MARKETPLACE_REPORT_RESOLVED", tenantId, null));
        return response;
    }

    @Transactional(readOnly = true)
    public PageResponse<MarketplaceReportResponse> getReports(UUID tenantId, Pageable pageable) {
        return PageResponse.from(
                reportRepository.findByTenantId(tenantId, pageable).map(this::toReportResponse));
    }

    @Transactional
    public void resolveReport(UUID tenantId, UUID adminId, UUID reportId, ResolveReportRequest request) {
        MarketplaceReport report = reportRepository.findByIdAndTenantId(reportId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("MarketplaceReport", reportId));
        ReportStatus newStatus = "RESOLVE".equalsIgnoreCase(request.action())
                ? ReportStatus.RESOLVED : ReportStatus.DISMISSED;
        report.setStatus(newStatus);
        report.setResolvedByUserId(adminId);
        report.setResolvedAt(OffsetDateTime.now());
        reportRepository.save(report);
        sseService.publishEvent(new SyncEvent("MARKETPLACE_REPORT_RESOLVED", tenantId, null));
        log.info("Report {} {} by admin {} in tenant {}", reportId, newStatus, adminId, tenantId);
    }

    // ─── Helpers ──────────────────────────────────────────────────────────

    private MarketplaceListing findOwnedListing(UUID tenantId, UUID userId, UUID id) {
        MarketplaceListing listing = listingRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("MarketplaceListing", id));
        if (!listing.getCreatedByUser().getId().equals(userId)) {
            throw new BusinessRuleConflictException("NOT_OWNER", "Solo el autor puede modificar esta publicación.");
        }
        return listing;
    }

    /**
     * SEC-9-9: una publicacion solo puede referenciar imagenes subidas por su autor a su carpeta de marketplace de esta
     * comunidad. Antes se aceptaba cualquier clave, que despues se servia publicamente como imagen de la publicacion.
     */
    private List<String> requireOwnImages(List<String> keys, UUID tenantId, UUID authorId) {
        for (String raw : keys) {
            boolean ok = com.havita.common.storage.StorageKey.parse(raw)
                    .filter(k -> MarketplaceImageFileRule.isOwnImage(k, tenantId, authorId))
                    .isPresent();
            if (!ok) {
                throw new BusinessRuleConflictException("INVALID_IMAGE_KEY", "Las imágenes deben subirse desde tu cuenta para esta publicación.");
            }
        }
        return keys;
    }

    /**
     * SEC-9-9: las imagenes se entregan como URLs firmadas de vida corta para quien consulta (no hay URLs publicas).
     * Cada URL vuelve a comprobar en el servidor el acceso vigente de ese usuario a la publicacion.
     */
    private List<String> signedImageUrls(MarketplaceListing listing) {
        java.util.Optional<com.havita.security.EffectiveAccess> viewer = com.havita.security.AuthorizationScope.current();
        if (viewer.isEmpty() || viewer.get().tenantId() == null) {
            return List.of();
        }
        return listing.getImageKeysList().stream()
                .map(com.havita.common.storage.StorageKey::parse)
                .flatMap(java.util.Optional::stream)
                .filter(k -> k.tenantId().equals(listing.getTenantId()))
                .map(k -> fileUrlSigner.sign(k, listing.getTenantId(), viewer.get().userId()))
                .toList();
    }

    private MarketplaceListingResponse toResponse(MarketplaceListing listing) {
        List<String> imageUrls = signedImageUrls(listing);
        User user = listing.getCreatedByUser();
        return new MarketplaceListingResponse(
                listing.getId(), listing.getType().name(), listing.getCategory(),
                listing.getTitle(), listing.getDescription(), listing.getPrice(),
                listing.getPriceType().name(), listing.getStatus().name(),
                imageUrls, listing.getImageKeysList(), listing.getContactPhone(), listing.getRejectionReason(),
                listing.isHiddenByAdmin(), listing.getHiddenReason(), listing.getHiddenAt(),
                listing.getPublishedAt(), listing.getExpiresAt(),
                listing.getCreatedAt(), listing.getUpdatedAt(),
                new MarketplaceListingResponse.AuthorInfo(
                        user.getId(), user.getFullName(), listing.getPropertyUnit()));
    }

    private MarketplaceReportResponse toReportResponse(MarketplaceReport report) {
        return new MarketplaceReportResponse(
                report.getId(), toResponse(report.getListing()),
                report.getReportedByUserId(), report.getReason().name(), report.getDescription(),
                report.getStatus().name(), report.getCreatedAt(),
                report.getResolvedAt(), report.getResolvedByUserId());
    }

    private ListingType parseType(String type) {
        if (type == null || type.isBlank()) return null;
        try { return ListingType.valueOf(type.toUpperCase()); } catch (Exception e) { return null; }
    }
}
