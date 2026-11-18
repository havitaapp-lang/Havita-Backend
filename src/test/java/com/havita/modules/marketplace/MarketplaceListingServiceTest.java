package com.havita.modules.marketplace;

import com.havita.common.exception.BusinessRuleConflictException;
import com.havita.common.exception.ResourceNotFoundException;
import com.havita.common.storage.ObjectStorageService;
import com.havita.modules.identity.domain.User;
import com.havita.modules.identity.infrastructure.UserRepository;
import com.havita.modules.marketplace.api.dto.*;
import com.havita.modules.marketplace.application.MarketplaceConfigService;
import com.havita.modules.marketplace.application.MarketplaceListingService;
import com.havita.modules.marketplace.domain.*;
import com.havita.modules.marketplace.infrastructure.MarketplaceListingRepository;
import com.havita.modules.marketplace.infrastructure.MarketplaceReportRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class MarketplaceListingServiceTest {

    @Mock private MarketplaceListingRepository listingRepository;
    @Mock private MarketplaceReportRepository reportRepository;
    @Mock private MarketplaceConfigService configService;
    @Mock private ObjectStorageService storageService;
    @Mock private UserRepository userRepository;
    @Mock private com.havita.modules.resident.infrastructure.ResidentRepository residentRepository;
    @Mock private com.havita.modules.sync.application.SseService sseService;

    @InjectMocks
    private MarketplaceListingService service;

    private UUID tenantId;
    private UUID userId;
    private User user;
    private CreateListingRequest createReq;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        userId = UUID.randomUUID();
        user = new User();
        user.setId(userId);
        createReq = new CreateListingRequest(ListingType.PRODUCT, "cat", "title", "desc", BigDecimal.TEN, PriceType.FIXED, null, "3001234567", true);
    }

    @Test
    void createListing_AutoApprove_SetsPublished() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(configService.getMode(tenantId)).thenReturn(MarketplaceMode.AUTO_APPROVE);
        when(configService.getListingDurationDays(tenantId)).thenReturn(30);
        when(listingRepository.save(any())).thenAnswer(i -> {
            MarketplaceListing l = i.getArgument(0);
            l.setId(UUID.randomUUID());
            return l;
        });

        var res = service.createListing(tenantId, userId, createReq);
        assertEquals(ListingStatus.PUBLISHED.name(), res.status());
        assertNotNull(res.publishedAt());
        assertNotNull(res.expiresAt());
    }

    @Test
    void createListing_RequireApproval_SetsPending() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(configService.getMode(tenantId)).thenReturn(MarketplaceMode.REQUIRE_APPROVAL);
        when(configService.getListingDurationDays(tenantId)).thenReturn(30);
        when(listingRepository.save(any())).thenAnswer(i -> {
            MarketplaceListing l = i.getArgument(0);
            l.setId(UUID.randomUUID());
            return l;
        });

        var res = service.createListing(tenantId, userId, createReq);
        assertEquals(ListingStatus.PENDING_APPROVAL.name(), res.status());
        assertNull(res.publishedAt());
        assertNull(res.expiresAt());
    }

    @Test
    void createListing_ContactPriceType_NoPriceNeeded() {
        CreateListingRequest req = new CreateListingRequest(ListingType.PRODUCT, "cat", "title", "desc", null, PriceType.CONTACT, null, "3001234567", true);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(configService.getMode(tenantId)).thenReturn(MarketplaceMode.AUTO_APPROVE);
        when(listingRepository.save(any())).thenAnswer(i -> {
            MarketplaceListing l = i.getArgument(0);
            l.setId(UUID.randomUUID());
            return l;
        });

        var res = service.createListing(tenantId, userId, req);
        assertNull(res.price());
    }

    @Test
    void createListing_FixedPriceWithoutPrice_ThrowsException() {
        CreateListingRequest req = new CreateListingRequest(ListingType.PRODUCT, "cat", "title", "desc", null, PriceType.FIXED, null, "3001234567", true);
        var ex = assertThrows(BusinessRuleConflictException.class, () -> service.createListing(tenantId, userId, req));
        assertEquals("PRICE_REQUIRED", ex.getCode());
    }

    @Test
    void createListing_TooManyImages_ThrowsException() {
        CreateListingRequest req = new CreateListingRequest(ListingType.PRODUCT, "cat", "title", "desc", BigDecimal.TEN, PriceType.FIXED, List.of("1","2","3","4","5","6"), "3001234567", true);
        var ex = assertThrows(BusinessRuleConflictException.class, () -> service.createListing(tenantId, userId, req));
        assertEquals("TOO_MANY_IMAGES", ex.getCode());
    }

    @Test
    void updateListing_NotOwner_ThrowsException() {
        UUID listingId = UUID.randomUUID();
        MarketplaceListing l = new MarketplaceListing();
        User u2 = new User(); u2.setId(UUID.randomUUID());
        l.setCreatedByUser(u2);

        when(listingRepository.findByIdAndTenantId(listingId, tenantId)).thenReturn(Optional.of(l));

        var req = new UpdateListingRequest(null, null, null, null, null, null, null);
        var ex = assertThrows(BusinessRuleConflictException.class, () -> service.updateListing(tenantId, userId, listingId, req));
        assertEquals("NOT_OWNER", ex.getCode());
    }

    // ─── Validacion: editar aplica las mismas reglas que crear ─────────

    private MarketplaceListing ownedListing(UUID listingId) {
        MarketplaceListing l = new MarketplaceListing();
        l.setId(listingId);
        l.setTenantId(tenantId);
        l.setCreatedByUser(user);
        l.setStatus(ListingStatus.PUBLISHED);
        l.setType(ListingType.PRODUCT);
        l.setCategory("cat");
        l.setTitle("Bicicleta");
        l.setDescription("Como nueva");
        l.setPriceType(PriceType.FIXED);
        l.setPrice(BigDecimal.TEN);
        l.setContactPhone("3001234567");
        when(listingRepository.findByIdAndTenantId(listingId, tenantId)).thenReturn(Optional.of(l));
        return l;
    }

    private static UpdateListingRequest phoneOnly(String phone) {
        return new UpdateListingRequest(null, null, null, null, null, null, phone);
    }

    @Test
    void createListing_PhoneWithLetters_Rejected() {
        var req = new CreateListingRequest(ListingType.PRODUCT, "cat", "title", "desc", BigDecimal.TEN, PriceType.FIXED, null, "300abc4567", true);
        var ex = assertThrows(com.havita.common.exception.InvalidRequestException.class, () -> service.createListing(tenantId, userId, req));
        assertEquals("INVALID_PHONE", ex.getCode());
        verify(listingRepository, never()).save(any());
    }

    @Test
    void createListing_NegativePrice_Rejected() {
        var req = new CreateListingRequest(ListingType.PRODUCT, "cat", "title", "desc", new BigDecimal("-1"), PriceType.FIXED, null, "3001234567", true);
        var ex = assertThrows(com.havita.common.exception.InvalidRequestException.class, () -> service.createListing(tenantId, userId, req));
        assertEquals("INVALID_PRICE", ex.getCode());
    }

    @Test
    void updateListing_PhoneWithLettersOrInvalid_RejectedWithoutSaving() {
        UUID id = UUID.randomUUID();
        MarketplaceListing l = ownedListing(id);
        for (String bad : new String[]{"llamame al 300", "300abc4567", "123", "+57 300 123 4567 ext 9", "<script>", "30012345678901234567890"}) {
            var ex = assertThrows(com.havita.common.exception.InvalidRequestException.class,
                    () -> service.updateListing(tenantId, userId, id, phoneOnly(bad)), bad);
            assertEquals("INVALID_PHONE", ex.getCode());
        }
        assertEquals("3001234567", l.getContactPhone());
        verify(listingRepository, never()).save(any());
    }

    @Test
    void updateListing_ValidPhone_NormalizedAndSaved() {
        UUID id = UUID.randomUUID();
        MarketplaceListing l = ownedListing(id);
        when(listingRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        service.updateListing(tenantId, userId, id, phoneOnly("  +57   300-123 4567 "));
        assertEquals("+57 300-123 4567", l.getContactPhone());
    }

    @Test
    void updateListing_BlankTitleOrDescription_Rejected() {
        UUID id = UUID.randomUUID();
        ownedListing(id);
        var title = assertThrows(com.havita.common.exception.InvalidRequestException.class,
                () -> service.updateListing(tenantId, userId, id, new UpdateListingRequest(null, "   ", null, null, null, null, "3001234567")));
        assertEquals("REQUIRED_FIELD", title.getCode());
        var desc = assertThrows(com.havita.common.exception.InvalidRequestException.class,
                () -> service.updateListing(tenantId, userId, id, new UpdateListingRequest(null, null, "", null, null, null, "3001234567")));
        assertEquals("REQUIRED_FIELD", desc.getCode());
        verify(listingRepository, never()).save(any());
    }

    @Test
    void updateListing_FixedPriceWithoutPriceOrNegative_Rejected() {
        UUID id = UUID.randomUUID();
        ownedListing(id);
        var missing = assertThrows(BusinessRuleConflictException.class,
                () -> service.updateListing(tenantId, userId, id, new UpdateListingRequest(null, null, null, null, PriceType.FIXED, null, "3001234567")));
        assertEquals("PRICE_REQUIRED", missing.getCode());
        var negative = assertThrows(com.havita.common.exception.InvalidRequestException.class,
                () -> service.updateListing(tenantId, userId, id, new UpdateListingRequest(null, null, null, new BigDecimal("-5"), PriceType.FIXED, null, "3001234567")));
        assertEquals("INVALID_PRICE", negative.getCode());
        verify(listingRepository, never()).save(any());
    }

    @Test
    void updateListing_PriceWithoutPriceType_UpdatesPriceKeepingType() {
        UUID id = UUID.randomUUID();
        MarketplaceListing l = ownedListing(id);
        when(listingRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        service.updateListing(tenantId, userId, id, new UpdateListingRequest(null, null, null, new BigDecimal("25000"), null, null, "3001234567"));
        assertEquals(PriceType.FIXED, l.getPriceType());
        assertEquals(new BigDecimal("25000"), l.getPrice());
    }

    @Test
    void updateListing_ToContactPrice_ClearsPrice() {
        UUID id = UUID.randomUUID();
        MarketplaceListing l = ownedListing(id);
        when(listingRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        service.updateListing(tenantId, userId, id, new UpdateListingRequest(null, null, null, null, PriceType.CONTACT, null, "3001234567"));
        assertNull(l.getPrice());
    }

    @Test
    void deleteListing_NotOwner_ThrowsException() {
        UUID listingId = UUID.randomUUID();
        MarketplaceListing l = new MarketplaceListing();
        User u2 = new User(); u2.setId(UUID.randomUUID());
        l.setCreatedByUser(u2);

        when(listingRepository.findByIdAndTenantId(listingId, tenantId)).thenReturn(Optional.of(l));

        var ex = assertThrows(BusinessRuleConflictException.class, () -> service.deleteListing(tenantId, userId, listingId));
        assertEquals("NOT_OWNER", ex.getCode());
    }

    @Test
    void deleteListing_Owner_SetsHiddenAndHiddenByAdminFalse() {
        UUID listingId = UUID.randomUUID();
        MarketplaceListing l = new MarketplaceListing();
        l.setId(listingId);
        l.setCreatedByUser(user);
        l.setStatus(ListingStatus.PUBLISHED);

        when(listingRepository.findByIdAndTenantId(listingId, tenantId)).thenReturn(Optional.of(l));

        service.deleteListing(tenantId, userId, listingId);

        assertEquals(ListingStatus.HIDDEN, l.getStatus());
        assertFalse(l.isHiddenByAdmin());
        assertNull(l.getHiddenReason());
        assertNotNull(l.getHiddenAt());
        verify(listingRepository).save(l);
    }

    @Test
    void unhideListing_OwnedAndHiddenByUser_Success() {
        UUID listingId = UUID.randomUUID();
        MarketplaceListing l = new MarketplaceListing();
        l.setId(listingId);
        l.setCreatedByUser(user);
        l.setType(ListingType.PRODUCT);
        l.setPriceType(PriceType.FIXED);
        l.setStatus(ListingStatus.HIDDEN);
        l.setHiddenByAdmin(false);
        l.setExpiresAt(OffsetDateTime.now().minusDays(1)); // expired while hidden

        when(listingRepository.findByIdAndTenantId(listingId, tenantId)).thenReturn(Optional.of(l));
        when(configService.getListingDurationDays(tenantId)).thenReturn(30);
        when(listingRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        var res = service.unhideListing(tenantId, userId, listingId);
        assertEquals(ListingStatus.PUBLISHED.name(), res.status());
        assertFalse(res.hiddenByAdmin());
        assertNull(res.hiddenReason());
        assertNotNull(res.expiresAt());
        assertTrue(res.expiresAt().isAfter(OffsetDateTime.now()));
    }

    @Test
    void unhideListing_HiddenByAdmin_ThrowsException() {
        UUID listingId = UUID.randomUUID();
        MarketplaceListing l = new MarketplaceListing();
        l.setId(listingId);
        l.setCreatedByUser(user);
        l.setStatus(ListingStatus.HIDDEN);
        l.setHiddenByAdmin(true);
        l.setHiddenReason("Infringió normas de convivencia");

        when(listingRepository.findByIdAndTenantId(listingId, tenantId)).thenReturn(Optional.of(l));

        var ex = assertThrows(BusinessRuleConflictException.class, () -> service.unhideListing(tenantId, userId, listingId));
        assertEquals("HIDDEN_BY_ADMIN", ex.getCode());
        verify(listingRepository, never()).save(any());
    }

    @Test
    void unhideListing_NotHidden_ThrowsException() {
        UUID listingId = UUID.randomUUID();
        MarketplaceListing l = new MarketplaceListing();
        l.setId(listingId);
        l.setCreatedByUser(user);
        l.setStatus(ListingStatus.PUBLISHED);

        when(listingRepository.findByIdAndTenantId(listingId, tenantId)).thenReturn(Optional.of(l));

        var ex = assertThrows(BusinessRuleConflictException.class, () -> service.unhideListing(tenantId, userId, listingId));
        assertEquals("NOT_HIDDEN", ex.getCode());
    }

    @Test
    void purgeListing_Success() {
        UUID listingId = UUID.randomUUID();
        MarketplaceListing l = new MarketplaceListing();
        l.setId(listingId);
        l.setCreatedByUser(user);

        when(listingRepository.findByIdAndTenantId(listingId, tenantId)).thenReturn(Optional.of(l));

        service.purgeListing(tenantId, userId, listingId);
        verify(listingRepository).delete(l);
    }

    @Test
    void adminHideListing_WithReason_SetsHiddenByAdminTrue() {
        UUID listingId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        MarketplaceListing l = new MarketplaceListing();
        l.setId(listingId);
        l.setCreatedByUser(user);
        l.setType(ListingType.PRODUCT);
        l.setPriceType(PriceType.FIXED);
        l.setStatus(ListingStatus.PUBLISHED);

        when(listingRepository.findByIdAndTenantId(listingId, tenantId)).thenReturn(Optional.of(l));
        when(listingRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        var req = new HideListingRequest("Contenido no permitido en el conjunto");
        var res = service.hideListing(tenantId, adminId, listingId, req);

        assertEquals(ListingStatus.HIDDEN.name(), res.status());
        assertTrue(res.hiddenByAdmin());
        assertEquals("Contenido no permitido en el conjunto", res.hiddenReason());
    }

    @Test
    void adminUnhideListing_Success() {
        UUID listingId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        MarketplaceListing l = new MarketplaceListing();
        l.setId(listingId);
        l.setCreatedByUser(user);
        l.setType(ListingType.PRODUCT);
        l.setPriceType(PriceType.FIXED);
        l.setStatus(ListingStatus.HIDDEN);
        l.setHiddenByAdmin(true);
        l.setHiddenReason("Incumplimiento previo");

        when(listingRepository.findByIdAndTenantId(listingId, tenantId)).thenReturn(Optional.of(l));
        when(configService.getListingDurationDays(tenantId)).thenReturn(30);
        when(listingRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        var res = service.adminUnhideListing(tenantId, adminId, listingId);
        assertEquals(ListingStatus.PUBLISHED.name(), res.status());
        assertFalse(res.hiddenByAdmin());
        assertNull(res.hiddenReason());
    }

    @Test
    void approveListing_NotPending_ThrowsException() {
        UUID listingId = UUID.randomUUID();
        MarketplaceListing l = new MarketplaceListing();
        l.setStatus(ListingStatus.PUBLISHED);

        when(listingRepository.findByIdAndTenantId(listingId, tenantId)).thenReturn(Optional.of(l));

        var ex = assertThrows(BusinessRuleConflictException.class, () -> service.approveListing(tenantId, UUID.randomUUID(), listingId));
        assertEquals("NOT_PENDING", ex.getCode());
    }

    @Test
    void approveListing_Success() {
        UUID listingId = UUID.randomUUID();
        MarketplaceListing l = new MarketplaceListing();
        l.setStatus(ListingStatus.PENDING_APPROVAL);
        l.setCreatedByUser(user);
        l.setType(ListingType.PRODUCT);
        l.setPriceType(PriceType.FIXED);

        when(listingRepository.findByIdAndTenantId(listingId, tenantId)).thenReturn(Optional.of(l));
        when(configService.getListingDurationDays(tenantId)).thenReturn(30);
        when(listingRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        var res = service.approveListing(tenantId, UUID.randomUUID(), listingId);
        assertEquals(ListingStatus.PUBLISHED.name(), res.status());
        assertNotNull(res.publishedAt());
        assertNotNull(res.expiresAt());
    }

    @Test
    void rejectListing_Success() {
        UUID listingId = UUID.randomUUID();
        MarketplaceListing l = new MarketplaceListing();
        l.setStatus(ListingStatus.PENDING_APPROVAL);
        l.setCreatedByUser(user);
        l.setType(ListingType.PRODUCT);
        l.setPriceType(PriceType.FIXED);

        when(listingRepository.findByIdAndTenantId(listingId, tenantId)).thenReturn(Optional.of(l));
        when(listingRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        var req = new RejectListingRequest("reason");
        var res = service.rejectListing(tenantId, UUID.randomUUID(), listingId, req);
        assertEquals(ListingStatus.REJECTED.name(), res.status());
        assertEquals("reason", res.rejectionReason());
    }

    @Test
    void reportListing_AlreadyReported_ThrowsException() {
        UUID listingId = UUID.randomUUID();
        MarketplaceListing l = new MarketplaceListing();
        l.setStatus(ListingStatus.PUBLISHED);
        User u2 = new User(); u2.setId(UUID.randomUUID());
        l.setCreatedByUser(u2);

        when(listingRepository.findByIdAndTenantId(listingId, tenantId)).thenReturn(Optional.of(l));
        when(reportRepository.existsByListing_IdAndReportedByUserId(listingId, userId)).thenReturn(true);

        var req = new ReportListingRequest(ReportReason.INAPPROPRIATE, "desc");
        var ex = assertThrows(BusinessRuleConflictException.class, () -> service.reportListing(tenantId, userId, listingId, req));
        assertEquals("ALREADY_REPORTED", ex.getCode());
    }

    @Test
    void reportListing_SelfReport_ThrowsException() {
        UUID listingId = UUID.randomUUID();
        MarketplaceListing l = new MarketplaceListing();
        l.setStatus(ListingStatus.PUBLISHED);
        l.setCreatedByUser(user);

        when(listingRepository.findByIdAndTenantId(listingId, tenantId)).thenReturn(Optional.of(l));

        var req = new ReportListingRequest(ReportReason.INAPPROPRIATE, "desc");
        var ex = assertThrows(BusinessRuleConflictException.class, () -> service.reportListing(tenantId, userId, listingId, req));
        assertEquals("SELF_REPORT", ex.getCode());
    }
}
