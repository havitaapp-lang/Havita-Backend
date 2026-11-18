package com.havita.modules.marketplace.application;

import com.havita.common.storage.StorageKey;
import com.havita.common.storage.StoredFileAccessRule;
import com.havita.modules.marketplace.domain.ListingStatus;
import com.havita.modules.marketplace.domain.MarketplaceListing;
import com.havita.modules.marketplace.infrastructure.MarketplaceListingRepository;
import com.havita.security.EffectiveAccess;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

/**
 * SEC-9-9: imagenes de {@code marketplace/{autor}/}. La lectura sigue la visibilidad de la publicacion que la usa:
 * <ul>
 *   <li>publicacion PUBLISHED: los roles que ven el marketplace de la comunidad;</li>
 *   <li>publicacion en cualquier otro estado (pendiente, rechazada, oculta, caducada): su autor y la administracion;</li>
 *   <li>imagen aun no asociada a ninguna publicacion: solo su autor (vista previa mientras crea la publicacion).</li>
 * </ul>
 * Solo suben residentes, siempre a su propia carpeta ({@code marketplace/{su id}}), que fija el servidor.
 */
@Component
@RequiredArgsConstructor
public class MarketplaceImageFileRule implements StoredFileAccessRule {

    static final Set<String> VIEWERS = Set.of("SUPER_ADMIN", "COMMUNITY_ADMIN", "SECURITY", "STAFF", "RESIDENT");
    static final Set<String> ADMINS = Set.of("SUPER_ADMIN", "COMMUNITY_ADMIN");
    static final Set<String> IMAGE_EXTENSIONS = Set.of("png", "jpg", "jpeg", "webp", "gif");

    private final MarketplaceListingRepository listingRepository;

    @Override
    public String folder() {
        return "marketplace";
    }

    @Override
    public Set<String> uploaderRoles() {
        return Set.of("RESIDENT");
    }

    @Override
    public Set<String> allowedExtensions() {
        return IMAGE_EXTENSIONS;
    }

    @Override
    public String uploadSubPath(EffectiveAccess uploader) {
        return uploader.userId().toString();
    }

    @Override
    public boolean inlineImages() {
        return true;
    }

    /** true si la clave es una imagen de la carpeta del autor indicado en esa comunidad. */
    static boolean isOwnImage(StorageKey key, java.util.UUID tenantId, java.util.UUID authorId) {
        return key.tenantId().equals(tenantId) && "marketplace".equals(key.folder())
                && key.subPath().equals(authorId.toString()) && IMAGE_EXTENSIONS.contains(key.extension());
    }

    @Override
    @Transactional(readOnly = true)
    public boolean canRead(StorageKey key, EffectiveAccess access) {
        String role = access.role().name();
        if (!VIEWERS.contains(role) || key.firstSubSegment().isEmpty()) {
            return false;
        }
        List<MarketplaceListing> listings = listingRepository.findByTenantIdReferencingImage(key.tenantId(), key.value()).stream()
                .filter(l -> l.getImageKeysList().contains(key.value()))
                .toList();
        if (listings.isEmpty()) {
            return key.subPath().equals(access.userId().toString());
        }
        for (MarketplaceListing listing : listings) {
            if (listing.getStatus() == ListingStatus.PUBLISHED) return true;
            if (ADMINS.contains(role)) return true;
            if (listing.getCreatedByUser() != null && listing.getCreatedByUser().getId().equals(access.userId())) return true;
        }
        return false;
    }
}
