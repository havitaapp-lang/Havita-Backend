package com.havita.modules.access.application;

import com.havita.modules.access.domain.PackageStatus;

import com.havita.common.exception.BusinessRuleConflictException;
import com.havita.common.exception.ResourceNotFoundException;
import com.havita.modules.access.api.dto.CreatePackageRequest;
import com.havita.modules.access.api.dto.PackageResponse;
import com.havita.modules.access.domain.Package;
import com.havita.modules.access.infrastructure.PackageRepository;
import com.havita.modules.identity.domain.User;
import com.havita.modules.identity.infrastructure.UserRepository;
import com.havita.modules.property.domain.Property;
import com.havita.modules.property.infrastructure.PropertyRepository;
import com.havita.security.AccessValidator;
import com.havita.security.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PackageService {

    private final PackageRepository packageRepository;
    private final PropertyRepository propertyRepository;
    private final UserRepository userRepository;
    private final com.havita.modules.sync.application.SyncEventPublisher syncEventPublisher;
    private final AccessValidator accessValidator;
    /** Avisos push a residentes (se envian tras el commit). */
    private final org.springframework.context.ApplicationEventPublisher events;

    @Transactional(readOnly = true)
    public Page<PackageResponse> findAll(String search, PackageStatus status, Pageable pageable) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        Page<Package> page;

        if (search != null && !search.isBlank()) {
            page = packageRepository.searchPackages(tenantId, search.trim(), pageable);
        } else if (status != null) {
            page = packageRepository.findByTenantIdAndStatus(tenantId, status, pageable);
        } else {
            page = packageRepository.findByTenantId(tenantId, pageable);
        }

        return page.map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public PackageResponse findById(UUID id) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        Package pkg = packageRepository.findByIdAndTenantId(id, tenantId)
                .filter(p -> !accessValidator.isResidentCaller()
                        || accessValidator.isActiveResidentOfProperty(p.getProperty().getId()))
                .orElseThrow(() -> new ResourceNotFoundException("Package", id));
        return toResponse(pkg);
    }

    @Transactional(readOnly = true)
    public List<PackageResponse> findByProperty(UUID propertyId) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        return packageRepository.findByTenantIdAndPropertyId(tenantId, propertyId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public PackageResponse receivePackage(CreatePackageRequest request, UUID guardUserId) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();

        Property property = propertyRepository.findByIdAndTenantId(request.propertyId(), tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Property", request.propertyId()));

        User guard = null;
        if (guardUserId != null) {
            guard = userRepository.findById(guardUserId).orElse(null);
        }

        Package pkg = Package.builder()
                .property(property)
                .recipientName(request.recipientName().trim())
                .trackingNumber(request.trackingNumber())
                .company(request.company() != null ? request.company() : "Otro")
                .status(PackageStatus.RECEIVED)
                .securityGuard(guard)
                .observations(request.observations())
                .build();
        pkg.setTenantId(tenantId);

        pkg = packageRepository.save(pkg);
        log.info("Paquete recibido para {} en propiedad {}", pkg.getRecipientName(), property.getNumber());
        
        PackageResponse response = toResponse(pkg);
        syncEventPublisher.publish(
            new com.havita.modules.sync.domain.SyncEvent(
                "PACKAGE_CREATED",
                tenantId,
                java.util.Map.of("package", response),
                com.havita.modules.sync.domain.SyncAudience.unit(pkg.getProperty().getId())
            )
        );
        events.publishEvent(com.havita.modules.push.application.ResidentPushRequested.unit(tenantId, pkg.getProperty().getId(),
                "Tienes un paquete en portería", "PACKAGE_RECEIVED", "/resident/packages"));
        
        return response;
    }

    @Transactional
    public PackageResponse notifyPackage(UUID id) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        Package pkg = packageRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Package", id));

        pkg.markAsNotified();
        pkg = packageRepository.save(pkg);
        log.info("Paquete notificado al destinatario: {}", pkg.getRecipientName());
        
        PackageResponse response = toResponse(pkg);
        syncEventPublisher.publish(
            new com.havita.modules.sync.domain.SyncEvent(
                "PACKAGE_NOTIFIED",
                tenantId,
                java.util.Map.of("package", response),
                com.havita.modules.sync.domain.SyncAudience.unit(pkg.getProperty().getId())
            )
        );
        events.publishEvent(com.havita.modules.push.application.ResidentPushRequested.unit(tenantId, pkg.getProperty().getId(),
                "Recuerda: tienes un paquete en portería", "PACKAGE_RECEIVED", "/resident/packages"));
        
        return response;
    }

    @Transactional
    public PackageResponse deliverPackage(UUID id, String deliveredTo) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        Package pkg = packageRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Package", id));

        pkg.deliver(deliveredTo != null && !deliveredTo.isBlank() ? deliveredTo : pkg.getRecipientName());
        pkg = packageRepository.save(pkg);
        
        syncEventPublisher.publish(
            new com.havita.modules.sync.domain.SyncEvent(
                "PACKAGE_STATUS_CHANGED",
                tenantId,
                java.util.Map.of(
                    "packageId", pkg.getId().toString(), 
                    "status", pkg.getStatus(),
                    "deliveredAt", pkg.getDeliveredAt().toString()
                ),
                com.havita.modules.sync.domain.SyncAudience.unit(pkg.getProperty().getId())
            )
        );
        
        log.info("Paquete entregado a {}", pkg.getDeliveredTo());
        return toResponse(pkg);
    }

    /**
     * El residente confirma la recogida de un paquete de SU unidad. La unidad sale del paquete (servidor) y se comprueba
     * contra las relaciones ACTIVE del residente en PostgreSQL: un paquete de otra unidad o comunidad responde 404 (no
     * revela que existe). Ya entregado: 409. {@code deliveredTo} deja constancia de que lo confirmo el residente.
     */
    @Transactional
    public PackageResponse confirmPickupByResident(UUID id) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        Package pkg = packageRepository.findByIdAndTenantId(id, tenantId)
                .filter(p -> p.getProperty() != null && accessValidator.isActiveResidentOfProperty(p.getProperty().getId()))
                .orElseThrow(() -> new ResourceNotFoundException("Package", id));
        if (pkg.getStatus() == PackageStatus.DELIVERED) {
            throw new com.havita.common.exception.BusinessRuleConflictException("PACKAGE_ALREADY_DELIVERED",
                    "Este paquete ya figura como entregado.");
        }
        String residentName = userRepository.findById(accessValidator.currentUserId())
                .map(com.havita.modules.identity.domain.User::getFullName).orElse("Residente");
        String who = residentName + " (confirmado por el residente)";
        return deliverPackage(id, who.length() > 150 ? who.substring(0, 150) : who);
    }

    @Transactional
    public void deletePackage(UUID id) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        Package pkg = packageRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Package", id));

        if (pkg.getStatus() == PackageStatus.DELIVERED) {
            throw new BusinessRuleConflictException(
                    "PACKAGE_ALREADY_DELIVERED",
                    "No se puede eliminar un paquete que ya ha sido entregado.");
        }

        packageRepository.delete(pkg);
        log.info("Paquete {} eliminado por solicitud para {}", pkg.getId(), pkg.getRecipientName());

        syncEventPublisher.publish(
            new com.havita.modules.sync.domain.SyncEvent(
                "PACKAGE_DELETED",
                tenantId,
                java.util.Map.of("packageId", pkg.getId().toString()),
                com.havita.modules.sync.domain.SyncAudience.unit(pkg.getProperty().getId())
            )
        );
    }

    private PackageResponse toResponse(Package pkg) {
        return new PackageResponse(
                pkg.getId(),
                pkg.getTenantId(),
                pkg.getProperty().getId(),
                pkg.getProperty().getNumber(),
                pkg.getProperty().getPath() != null && !pkg.getProperty().getPath().isBlank()
                        ? pkg.getProperty().getPath()
                        : (pkg.getProperty().getName() != null ? pkg.getProperty().getName() : pkg.getProperty().getNumber()),
                pkg.getRecipientName(),
                pkg.getTrackingNumber(),
                pkg.getCompany(),
                pkg.getStatus(),
                pkg.getReceivedAt(),
                pkg.getDeliveredAt(),
                pkg.getDeliveredTo(),
                pkg.getSecurityGuard() != null ? pkg.getSecurityGuard().getId() : null,
                pkg.getObservations(),
                pkg.getCreatedAt(),
                pkg.getUpdatedAt()
        );
    }
}
