package com.havita.modules.assembly.application;

import com.havita.common.exception.BusinessRuleConflictException;
import com.havita.common.exception.InvalidRequestException;
import com.havita.common.exception.ResourceNotFoundException;
import com.havita.security.TenantContextHolder;
import com.havita.modules.assembly.api.dto.AssemblyProxyResponse;
import com.havita.modules.assembly.api.dto.AttendeeResponse;
import com.havita.modules.assembly.domain.Assembly;
import com.havita.modules.assembly.domain.AssemblyAttendee;
import com.havita.modules.assembly.domain.AssemblyStatus;
import com.havita.modules.property.domain.Property;
import com.havita.modules.assembly.api.dto.CreateAssemblyProxyRequest;
import com.havita.modules.assembly.domain.AssemblyProxy;
import com.havita.modules.assembly.domain.AssemblyProxyChannel;
import com.havita.modules.assembly.domain.AssemblyProxyStatus;
import com.havita.modules.assembly.infrastructure.AssemblyProxyJdbcRepository;
import com.havita.modules.identity.application.TenantUserResolver;
import com.havita.modules.identity.domain.RoleType;
import com.havita.modules.property.infrastructure.PropertyRepository;
import com.havita.security.AccessValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Fase 2 / P1 — poderes verificables para asambleas (MODELO PREPARADO, NO ACTIVADO). Solo existe con
 * {@code havita.assembly-proxies.enabled=true} (por defecto false) y requiere la tabla de V102.
 *
 * Alcance de P1: solicitar (propietario), registrar en nombre del propietario (administracion), aprobar, rechazar,
 * revocar, reemplazar el documento mientras esta en revision, vencer los poderes de una asamblea finalizada y leer el
 * documento. NO usa los poderes en asistencia, quorum ni votacion, y no marca ninguno como USED.
 *
 * Orden de bloqueos (compatible con E3/E5: membresia → pregunta → unidad → asistencia → asamblea):
 * membresia del llamante (FOR SHARE) → unidad (FOR NO KEY UPDATE, la misma fila que toman delegar y registrar
 * asistencia) → relacion del otorgante (FOR SHARE) → poder (FOR NO KEY UPDATE) → identidad del apoderado (bloqueo
 * consultivo, solo si hay limite) → asamblea (FOR SHARE, choca con finalizar).
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "havita.assembly-proxies.enabled", havingValue = "true")
public class AssemblyProxyService {

    private static final Set<RoleType> OWNER_ROLES = Set.of(RoleType.RESIDENT);
    private static final Set<RoleType> OWNER_OR_ADMIN_ROLES = Set.of(RoleType.RESIDENT, RoleType.COMMUNITY_ADMIN, RoleType.SUPER_ADMIN);
    private static final Set<RoleType> GLOBAL_ADMIN = Set.of(RoleType.SUPER_ADMIN);

    private final AssemblyProxyJdbcRepository proxies;
    private final AssemblyProxyEvents events;
    private final ProxyDocumentStorage documents;
    private final AccessValidator accessValidator;
    private final PropertyRepository propertyRepository;
    private final TenantUserResolver tenantUserResolver;
    private final AssemblyService assemblyService;
    private final com.havita.modules.assembly.infrastructure.AssemblyRepository assemblyRepository;
    private final long maxDocumentBytes;
    /** Fase 2 / P5-D: limite de la comunidad (V106) o el global de respaldo. */
    private final ProxyLimitPolicy limits;

    public AssemblyProxyService(AssemblyProxyJdbcRepository proxies, AssemblyProxyEvents events, ProxyDocumentStorage documents,
                                AccessValidator accessValidator, PropertyRepository propertyRepository,
                                TenantUserResolver tenantUserResolver, AssemblyService assemblyService,
                                com.havita.modules.assembly.infrastructure.AssemblyRepository assemblyRepository,
                                @Value("${havita.assembly-proxies.max-document-bytes:5242880}") long maxDocumentBytes,
                                ProxyLimitPolicy limits) {
        this.proxies = proxies;
        this.events = events;
        this.documents = documents;
        this.accessValidator = accessValidator;
        this.propertyRepository = propertyRepository;
        this.tenantUserResolver = tenantUserResolver;
        this.assemblyService = assemblyService;
        this.assemblyRepository = assemblyRepository;
        this.maxDocumentBytes = maxDocumentBytes;
        // P1-R9: sin limite por defecto. El limite real depende del reglamento de cada comunidad (no se inventa); P5-D:
        // configurable por comunidad.
        this.limits = limits;
    }

    // ── solicitud y registro ──────────────────────────────────────────────────

    /** P1-R1/R2: un propietario ACTIVO de la unidad (cualquier copropietario) solicita el poder en su propio nombre. */
    @Transactional
    public AssemblyProxyResponse requestByOwner(UUID assemblyId, CreateAssemblyProxyRequest req, byte[] document) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        accessValidator.lockAuthorization(OWNER_ROLES, Set.of());
        UUID userId = accessValidator.currentUserId();
        ProxyDocumentInspector.DetectedType type = ProxyDocumentInspector.inspect(document, maxDocumentBytes);
        if (req.grantorResidentId() != null) {
            throw new InvalidRequestException("GRANTOR_NOT_SELECTABLE", "El otorgante de tu solicitud eres tú; no se puede indicar otro.");
        }
        if (req.evidenceType() != null || req.grantorConsentAttested() != null) {
            throw new InvalidRequestException("EVIDENCE_NOT_APPLICABLE",
                    "La evidencia administrativa solo se indica cuando la administración registra el poder.");
        }
        lockUnit(req.propertyId(), tenantId);
        UUID grantorResidentId = proxies.lockActiveOwnerRelationOfUser(userId, req.propertyId(), tenantId)
                .orElseThrow(() -> new BusinessRuleConflictException("NOT_THE_OWNER",
                        "Solamente un propietario activo de la unidad puede otorgar un poder."));
        return create(tenantId, assemblyId, req, document, type, grantorResidentId, userId, userId, AssemblyProxyChannel.OWNER);
    }

    /** P1-R5: la administracion registra el poder en nombre de un propietario ACTIVO de la unidad (puede aprobarlo despues). */
    @Transactional
    public AssemblyProxyResponse registerByAdmin(UUID assemblyId, CreateAssemblyProxyRequest req, byte[] document) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        accessValidator.lockCommunityAdministrator();
        UUID adminId = accessValidator.currentUserId();
        ProxyDocumentInspector.DetectedType type = ProxyDocumentInspector.inspect(document, maxDocumentBytes);
        if (req.grantorResidentId() == null) {
            throw new InvalidRequestException("GRANTOR_REQUIRED", "Indica la relación de propietario que otorga el poder.");
        }
        // P7 (G3): la administracion no crea consentimiento. Debe indicar como recibio el poder firmado por el otorgante
        // (el archivo adjunto) y declararlo; ambos quedan en la auditoria. El poder entra EN REVISION como cualquier otro.
        if (req.evidenceType() == null) {
            throw new InvalidRequestException("EVIDENCE_TYPE_REQUIRED", "Indica cómo se recibió el poder firmado por el otorgante.");
        }
        if (!Boolean.TRUE.equals(req.grantorConsentAttested())) {
            throw new InvalidRequestException("GRANTOR_CONSENT_ATTESTATION_REQUIRED",
                    "Confirma que el documento adjunto es el poder firmado por el otorgante.");
        }
        lockUnit(req.propertyId(), tenantId);
        UUID[] grantor = proxies.lockActiveOwnerRelation(req.grantorResidentId(), req.propertyId(), tenantId)
                .orElseThrow(() -> new BusinessRuleConflictException("GRANTOR_NOT_ACTIVE_OWNER",
                        "El otorgante debe ser un propietario activo de la unidad."));
        return create(tenantId, assemblyId, req, document, type, grantor[0], grantor[1], adminId, AssemblyProxyChannel.ADMIN);
    }

    private AssemblyProxyResponse create(UUID tenantId, UUID assemblyId, CreateAssemblyProxyRequest req, byte[] document,
                                         ProxyDocumentInspector.DetectedType type, UUID grantorResidentId, UUID grantorUserId,
                                         UUID actorId, AssemblyProxyChannel channel) {
        UUID proxyUserId = req.proxyUserId();
        if (proxyUserId != null) {
            // P1-R6: apoderado con cuenta, resuelto solo dentro de esta comunidad (404 si no, sin revelar si existe).
            tenantUserResolver.requireUserInTenant(proxyUserId, tenantId);
            if (proxyUserId.equals(grantorUserId)) {
                throw new InvalidRequestException("PROXY_CANNOT_BE_GRANTOR", "El apoderado no puede ser el propio otorgante.");
            }
            requireActiveProxyMembership(proxyUserId, tenantId); // P2 / R2
        }
        requireOpenAssembly(assemblyId, tenantId);
        if (proxies.existsLive(assemblyId, req.propertyId(), tenantId)) {
            throw new BusinessRuleConflictException("PROXY_ALREADY_EXISTS",
                    "La unidad ya tiene un poder en revisión o aprobado para esta asamblea.");
        }
        requireNoCompetingRepresentation(assemblyId, req.propertyId(), tenantId);

        UUID proxyId = UUID.randomUUID(); // identificador de la operacion: tambien en el nombre del objeto (P5-C)
        String key = documents.store(tenantId, proxyId, document, type);
        discardOnRollback(key);
        OffsetDateTime now = OffsetDateTime.now();
        AssemblyProxy proxy = new AssemblyProxy(proxyId, tenantId, assemblyId, req.propertyId(), grantorResidentId,
                grantorUserId, proxyUserId, req.proxyFullName().trim(), req.proxyDocumentType(), req.proxyDocumentNumber().trim(),
                AssemblyProxyStatus.PENDING_REVIEW, key, type.contentType, document.length, ProxyDocumentInspector.sha256(document),
                now, actorId, actorId, channel, null, null, null, null, null, null, null, null, null, now, now, 0);
        proxies.insert(proxy); // el indice uq_assembly_proxy_live_per_unit resuelve la carrera entre copropietarios (409)
        events.success(tenantId, assemblyId, req.propertyId(), proxy.id(), actorId, channel, "PROXY_REQUESTED", null,
                AssemblyProxyStatus.PENDING_REVIEW);
        if (channel == AssemblyProxyChannel.ADMIN) {
            // P7 (G3): el tipo de evidencia queda en el evento de registro del documento (historial de solo insercion,
            // sin migracion nueva). Nunca se registra el numero de documento.
            events.successWithReason(tenantId, assemblyId, req.propertyId(), proxy.id(), actorId, channel, "PROXY_DOCUMENT_REGISTERED",
                    null, null, "EVIDENCE_" + req.evidenceType());
        } else {
            events.success(tenantId, assemblyId, req.propertyId(), proxy.id(), actorId, channel, "PROXY_DOCUMENT_REGISTERED", null, null);
        }
        return toResponse(proxy);
    }

    // ── revision ──────────────────────────────────────────────────────────────

    @Transactional
    public AssemblyProxyResponse approve(UUID assemblyId, UUID proxyId) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        accessValidator.lockCommunityAdministrator();
        UUID adminId = accessValidator.currentUserId();
        AssemblyProxy p = lockForChange(assemblyId, proxyId, tenantId);
        Optional<UUID[]> grantor = proxies.lockActiveOwnerRelation(p.grantorResidentId(), p.propertyId(), tenantId);
        p = proxies.lock(proxyId, assemblyId, tenantId).orElseThrow();
        requireTransition(p, AssemblyProxyStatus.APPROVED, adminId, AssemblyProxyChannel.ADMIN);
        if (grantor.isEmpty()) {
            throw new BusinessRuleConflictException("GRANTOR_NOT_ACTIVE_OWNER",
                    "El otorgante ya no es propietario activo de la unidad; el poder no puede aprobarse.");
        }
        if (p.proxyUserId() != null) {
            requireActiveProxyMembership(p.proxyUserId(), tenantId); // P2 / R2
        }
        requireWithinLimit(p, tenantId);
        requireOpenAssembly(assemblyId, tenantId);
        requireNoCompetingRepresentation(assemblyId, p.propertyId(), tenantId);
        proxies.approve(p.id(), adminId, OffsetDateTime.now());
        events.success(tenantId, assemblyId, p.propertyId(), p.id(), adminId, AssemblyProxyChannel.ADMIN, "PROXY_APPROVED",
                p.status(), AssemblyProxyStatus.APPROVED);
        return toResponse(proxies.find(proxyId, assemblyId, tenantId).orElseThrow());
    }

    @Transactional
    public AssemblyProxyResponse reject(UUID assemblyId, UUID proxyId, String reason) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        accessValidator.lockCommunityAdministrator();
        UUID adminId = accessValidator.currentUserId();
        if (reason == null || reason.isBlank()) {
            throw new InvalidRequestException("REJECTION_REASON_REQUIRED", "Indica el motivo del rechazo.");
        }
        AssemblyProxy p = lockForChange(assemblyId, proxyId, tenantId);
        p = proxies.lock(proxyId, assemblyId, tenantId).orElseThrow();
        requireTransition(p, AssemblyProxyStatus.REJECTED, adminId, AssemblyProxyChannel.ADMIN);
        proxies.reject(p.id(), adminId, OffsetDateTime.now(), reason.trim());
        events.success(tenantId, assemblyId, p.propertyId(), p.id(), adminId, AssemblyProxyChannel.ADMIN, "PROXY_REJECTED",
                p.status(), AssemblyProxyStatus.REJECTED);
        return toResponse(proxies.find(proxyId, assemblyId, tenantId).orElseThrow());
    }

    /**
     * P1-R8: revoca el otorgante (mientras siga siendo propietario activo) o la administracion, antes de que el poder se
     * use. Un poder USED no se revoca por este flujo. Para cualquier otro residente el poder "no existe" (404).
     */
    @Transactional
    public AssemblyProxyResponse revoke(UUID assemblyId, UUID proxyId, String reason) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        accessValidator.lockAuthorization(OWNER_OR_ADMIN_ROLES, GLOBAL_ADMIN);
        UUID actorId = accessValidator.currentUserId();
        AssemblyProxyChannel channel = accessValidator.isCommunityAdministrator() ? AssemblyProxyChannel.ADMIN : AssemblyProxyChannel.OWNER;
        AssemblyProxy p = lockForChange(assemblyId, proxyId, tenantId);
        if (channel == AssemblyProxyChannel.OWNER) {
            requireGrantor(p, actorId, tenantId);
        }
        p = proxies.lock(proxyId, assemblyId, tenantId).orElseThrow();
        if (p.status() == AssemblyProxyStatus.USED) {
            events.denied(tenantId, assemblyId, p.propertyId(), p.id(), actorId, channel, p.status(), AssemblyProxyStatus.REVOKED, "PROXY_ALREADY_USED");
            throw new BusinessRuleConflictException("PROXY_ALREADY_USED",
                    "El poder ya se utilizó para registrar asistencia y no puede revocarse por este medio.");
        }
        requireTransition(p, AssemblyProxyStatus.REVOKED, actorId, channel);
        requireOpenAssembly(assemblyId, tenantId);
        proxies.revoke(p.id(), actorId, OffsetDateTime.now(), reason != null && !reason.isBlank() ? reason.trim() : null);
        events.success(tenantId, assemblyId, p.propertyId(), p.id(), actorId, channel, "PROXY_REVOKED", p.status(), AssemblyProxyStatus.REVOKED);
        return toResponse(proxies.find(proxyId, assemblyId, tenantId).orElseThrow());
    }

    /** Solo mientras el poder esta en revision; un poder aprobado no cambia de documento (se revoca y se pide otro). */
    @Transactional
    public AssemblyProxyResponse replaceDocument(UUID assemblyId, UUID proxyId, byte[] document) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        accessValidator.lockAuthorization(OWNER_OR_ADMIN_ROLES, GLOBAL_ADMIN);
        UUID actorId = accessValidator.currentUserId();
        AssemblyProxyChannel channel = accessValidator.isCommunityAdministrator() ? AssemblyProxyChannel.ADMIN : AssemblyProxyChannel.OWNER;
        ProxyDocumentInspector.DetectedType type = ProxyDocumentInspector.inspect(document, maxDocumentBytes);
        AssemblyProxy p = lockForChange(assemblyId, proxyId, tenantId);
        if (channel == AssemblyProxyChannel.OWNER) {
            requireGrantor(p, actorId, tenantId);
        }
        p = proxies.lock(proxyId, assemblyId, tenantId).orElseThrow();
        if (p.status() != AssemblyProxyStatus.PENDING_REVIEW) {
            events.denied(tenantId, assemblyId, p.propertyId(), p.id(), actorId, channel, p.status(), p.status(), "PROXY_NOT_EDITABLE");
            throw new BusinessRuleConflictException("PROXY_NOT_EDITABLE",
                    "El documento solo puede cambiarse mientras el poder está en revisión. Revoca el poder y solicita uno nuevo.");
        }
        String key = documents.store(tenantId, p.id(), document, type);
        discardOnRollback(key);
        proxies.replaceDocument(p.id(), key, type.contentType, document.length, ProxyDocumentInspector.sha256(document),
                actorId, OffsetDateTime.now());
        events.success(tenantId, assemblyId, p.propertyId(), p.id(), actorId, channel, "PROXY_DOCUMENT_REPLACED", p.status(), p.status());
        return toResponse(proxies.find(proxyId, assemblyId, tenantId).orElseThrow());
    }

    /**
     * Vence los poderes en revision o aprobados (no utilizados) de una asamblea FINALIZADA. No toca poderes USED,
     * asistencias, votos ni resultados: solo el estado del poder, con su fecha y su evento.
     */
    @Transactional
    public int expireForEndedAssembly(UUID assemblyId) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        accessValidator.lockCommunityAdministrator();
        UUID adminId = accessValidator.currentUserId();
        List<AssemblyProxy> expirable = proxies.lockExpirable(assemblyId, tenantId);
        String status = proxies.lockAssemblyStatus(assemblyId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Assembly", assemblyId));
        if (!"ENDED".equals(status)) {
            throw new BusinessRuleConflictException("ASSEMBLY_NOT_ENDED", "Solo vencen los poderes de una asamblea finalizada.");
        }
        OffsetDateTime now = OffsetDateTime.now();
        for (AssemblyProxy p : expirable) {
            proxies.expire(p.id(), now);
            events.success(tenantId, assemblyId, p.propertyId(), p.id(), adminId, AssemblyProxyChannel.ADMIN, "PROXY_EXPIRED",
                    p.status(), AssemblyProxyStatus.EXPIRED);
        }
        return expirable.size();
    }

    // ── Fase 2 / P2: asistencia mediante poder ────────────────────────────────

    /** Roles de comunidad con los que un apoderado con cuenta puede actuar (membresia ACTIVA comprobada en PostgreSQL). */
    private static final Set<RoleType> COMMUNITY_MEMBER_ROLES = Set.of(RoleType.COMMUNITY_ADMIN, RoleType.SECURITY,
            RoleType.STAFF, RoleType.RESIDENT, RoleType.ACCOUNTANT);

    /** Identidad presentada en persona ante la administracion (debe coincidir con la del poder aprobado). */
    public record PresentedIdentity(String documentType, String documentNumber) {}

    /**
     * El propio apoderado CON CUENTA registra su asistencia en una asamblea EN CURSO. Solo con su cuenta: un poder de
     * otra persona "no existe" (404). Su membresia en la comunidad debe estar ACTIVA (PostgreSQL, no el JWT).
     */
    @Transactional
    public AttendeeResponse registerAttendanceAsProxy(UUID assemblyId, UUID proxyId) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        accessValidator.lockAuthorization(COMMUNITY_MEMBER_ROLES, Set.of());
        UUID actorId = accessValidator.currentUserId();
        AssemblyProxy p = proxies.find(proxyId, assemblyId, tenantId)
                .filter(x -> actorId != null && actorId.equals(x.proxyUserId()))
                .orElseThrow(() -> new ResourceNotFoundException("AssemblyProxy", proxyId));
        return useForAttendance(p, actorId, AssemblyProxyChannel.PROXY, null, tenantId);
    }

    /**
     * La administracion registra la asistencia PRESENCIAL del apoderado (con o sin cuenta) tras verificar su identidad:
     * tipo y numero de documento presentados deben coincidir con los del poder aprobado. Queda registrado quien verifico
     * y cuando. Asamblea programada (pre-registro, como D13) o en curso. No se crea ninguna cuenta.
     */
    @Transactional
    public AttendeeResponse registerAttendanceByAdmin(UUID assemblyId, UUID proxyId, PresentedIdentity identity) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        accessValidator.lockCommunityAdministrator();
        UUID adminId = accessValidator.currentUserId();
        AssemblyProxy p = proxies.find(proxyId, assemblyId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("AssemblyProxy", proxyId));
        return useForAttendance(p, adminId, AssemblyProxyChannel.ADMIN, identity, tenantId);
    }

    /**
     * Una sola transaccion: validar, bloquear (membresia, unidad, otorgante, poder, membresia del apoderado, identidad,
     * asamblea), registrar la asistencia, marcar el poder USED y auditar. Cualquier fallo revierte todo: nunca queda un
     * poder USED sin asistencia ni una asistencia por poder sin poder USED (tambien lo exige V103 en PostgreSQL).
     */
    private AttendeeResponse useForAttendance(AssemblyProxy found, UUID actorId, AssemblyProxyChannel channel,
                                              PresentedIdentity identity, UUID tenantId) {
        UUID assemblyId = found.assemblyId();
        lockUnit(found.propertyId(), tenantId);
        Optional<UUID[]> grantor = proxies.lockActiveOwnerRelation(found.grantorResidentId(), found.propertyId(), tenantId);
        AssemblyProxy p = proxies.lock(found.id(), assemblyId, tenantId).orElseThrow();
        if (p.status() == AssemblyProxyStatus.USED) {
            events.deniedAction(tenantId, assemblyId, p.propertyId(), p.id(), actorId, channel, "PROXY_ATTENDANCE_DENIED",
                    p.status(), "PROXY_ALREADY_USED");
            throw new BusinessRuleConflictException("PROXY_ALREADY_USED", "El poder ya se utilizó para registrar asistencia.");
        }
        if (p.status() != AssemblyProxyStatus.APPROVED) {
            events.deniedAction(tenantId, assemblyId, p.propertyId(), p.id(), actorId, channel, "PROXY_ATTENDANCE_DENIED",
                    p.status(), "PROXY_NOT_APPROVED");
            throw new BusinessRuleConflictException("PROXY_NOT_APPROVED", "Solo un poder aprobado permite registrar asistencia.");
        }
        if (p.documentKey() == null || p.documentSha256() == null) {
            throw new BusinessRuleConflictException("PROXY_DOCUMENT_REQUIRED", "El poder no tiene documento de soporte.");
        }
        if (grantor.isEmpty()) {
            throw new BusinessRuleConflictException("GRANTOR_NOT_ACTIVE_OWNER",
                    "El otorgante ya no es propietario activo de la unidad; el poder no puede usarse.");
        }
        if (p.proxyUserId() != null) {
            requireActiveProxyMembership(p.proxyUserId(), tenantId);
        }
        OffsetDateTime verifiedAt = null;
        if (identity != null) {
            boolean matches = identity.documentType() != null && identity.documentNumber() != null
                    && p.proxyDocumentType().equalsIgnoreCase(identity.documentType().trim())
                    && p.proxyDocumentNumber().equalsIgnoreCase(identity.documentNumber().trim());
            if (!matches) {
                events.deniedAction(tenantId, assemblyId, p.propertyId(), p.id(), actorId, channel, "PROXY_ATTENDANCE_DENIED",
                        p.status(), "PROXY_IDENTITY_MISMATCH");
                throw new BusinessRuleConflictException("PROXY_IDENTITY_MISMATCH",
                        "El documento presentado no coincide con el del apoderado del poder.");
            }
            verifiedAt = OffsetDateTime.now();
        }
        requireWithinLimit(p, tenantId);
        requireNoCompetingRepresentation(assemblyId, p.propertyId(), tenantId);

        Assembly assembly = assemblyRepository.findByIdAndTenantId(assemblyId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Assembly", assemblyId));
        assemblyService.lockAssemblyForAttendance(assembly, tenantId); // FOR NO KEY UPDATE + estado releido
        if (channel == AssemblyProxyChannel.PROXY && assembly.getStatus() != AssemblyStatus.IN_PROGRESS) {
            throw new BusinessRuleConflictException("ASSEMBLY_NOT_IN_PROGRESS", "La asamblea no está en curso para registrar asistencia.");
        }
        if (assembly.getStatus() == AssemblyStatus.ENDED) {
            throw new BusinessRuleConflictException("ASSEMBLY_ENDED", "La asamblea ya finalizó; no se registran más asistencias.");
        }
        Property property = propertyRepository.findByIdAndTenantId(p.propertyId(), tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Property", p.propertyId()));

        AssemblyAttendee attendee = assemblyService.saveProxyAttendee(assembly, property, p.proxyFullName(), tenantId);
        OffsetDateTime now = OffsetDateTime.now();
        boolean byAdmin = channel == AssemblyProxyChannel.ADMIN;
        proxies.markUsed(p.id(), attendee.getId(), byAdmin ? "ADMIN" : "PROXY", byAdmin ? actorId : null, verifiedAt, now);
        if (verifiedAt != null) {
            events.success(tenantId, assemblyId, p.propertyId(), p.id(), actorId, channel, "PROXY_IDENTITY_VERIFIED", p.status(), p.status());
        }
        events.success(tenantId, assemblyId, p.propertyId(), p.id(), actorId, channel, "PROXY_ATTENDANCE_REGISTERED", p.status(), p.status());
        events.success(tenantId, assemblyId, p.propertyId(), p.id(), actorId, channel, "PROXY_USED",
                AssemblyProxyStatus.APPROVED, AssemblyProxyStatus.USED);
        return new AttendeeResponse(attendee.getId(), assemblyId, property.getId(), property.getNumber(), property.getName(),
                null, null, attendee.getCoefficient(), true, attendee.getRepresentativeName(), attendee.getCheckedInAt());
    }

    /**
     * Limite de unidades por apoderado (P1-R9; P5-D por comunidad): la configuracion de la comunidad se lee con FOR SHARE
     * (un cambio concurrente espera o ya se ve) y las aprobaciones/usos del mismo apoderado se serializan con el bloqueo
     * consultivo de su identidad. Sin limite configurado no se cuenta nada.
     */
    private void requireWithinLimit(AssemblyProxy p, UUID tenantId) {
        Integer max = limits.resolveLocked(tenantId);
        if (max == null) {
            return;
        }
        proxies.lockProxyIdentity(p.assemblyId(), p.proxyDocumentType(), p.proxyDocumentNumber());
        long represented = proxies.countApprovedForProxy(p.assemblyId(), tenantId, p.id(), p.proxyUserId(),
                p.proxyDocumentType(), p.proxyDocumentNumber());
        if (represented >= max) {
            throw new BusinessRuleConflictException("PROXY_REPRESENTATION_LIMIT_REACHED",
                    "El apoderado ya representa el número máximo de unidades permitido para esta asamblea.");
        }
    }

    /** R2: la cuenta del apoderado tiene membresia ACTIVA en la comunidad (y cuenta activa), bloqueada FOR SHARE. */
    private void requireActiveProxyMembership(UUID proxyUserId, UUID tenantId) {
        if (!proxies.lockActiveMembership(proxyUserId, tenantId)) {
            throw new BusinessRuleConflictException("PROXY_USER_NOT_ACTIVE_MEMBER",
                    "La cuenta del apoderado no tiene una membresía activa en esta comunidad.");
        }
    }

    // ── lectura ───────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<AssemblyProxyResponse> listForAdmin(UUID assemblyId) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        accessValidator.requireCommunityAdministrator();
        return proxies.findByAssembly(assemblyId, tenantId).stream().map(this::toResponse).toList();
    }

    /**
     * Fase 2 / P6: poderes de la asamblea relacionados con el usuario: los que otorgo (solo mientras siga siendo
     * propietario activo de la unidad, como para el documento) y aquellos en que es el apoderado con cuenta. Asamblea de
     * otra comunidad: 404. Nunca devuelve poderes de terceros.
     */
    @Transactional(readOnly = true)
    public List<com.havita.modules.assembly.api.dto.MyAssemblyProxyResponse> listMine(UUID assemblyId) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        UUID userId = accessValidator.currentUserId();
        assemblyRepository.findByIdAndTenantId(assemblyId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Assembly", assemblyId));
        if (userId == null) {
            return List.of();
        }
        List<com.havita.modules.assembly.api.dto.MyAssemblyProxyResponse> out = new java.util.ArrayList<>();
        for (AssemblyProxy p : proxies.findByAssembly(assemblyId, tenantId)) {
            if (userId.equals(p.proxyUserId())) {
                out.add(new com.havita.modules.assembly.api.dto.MyAssemblyProxyResponse("PROXY", toResponse(p)));
            } else if (userId.equals(p.grantorUserId())
                    && proxies.isActiveOwnerRelation(p.grantorResidentId(), p.propertyId(), tenantId)) {
                out.add(new com.havita.modules.assembly.api.dto.MyAssemblyProxyResponse("GRANTOR", toResponse(p)));
            }
        }
        return out;
    }

    /** Fase 2 / P6: historial de eventos de un poder (solo la administracion vigente de la comunidad; 404 si no es suyo). */
    @Transactional(readOnly = true)
    public List<com.havita.modules.assembly.api.dto.AssemblyProxyEventResponse> events(UUID assemblyId, UUID proxyId) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        accessValidator.requireCommunityAdministrator();
        AssemblyProxy p = proxies.find(proxyId, assemblyId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("AssemblyProxy", proxyId));
        return proxies.findEvents(p.id(), tenantId);
    }

    public record DocumentContent(String contentType, byte[] bytes) {}

    /** Documento del poder: solo la administracion de la comunidad o el otorgante (propietario activo). Queda auditado. */
    @Transactional
    public DocumentContent readDocument(UUID assemblyId, UUID proxyId) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        accessValidator.lockAuthorization(OWNER_OR_ADMIN_ROLES, GLOBAL_ADMIN);
        UUID actorId = accessValidator.currentUserId();
        boolean admin = accessValidator.isCommunityAdministrator();
        AssemblyProxy p = proxies.find(proxyId, assemblyId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("AssemblyProxy", proxyId));
        if (!admin) {
            requireGrantor(p, actorId, tenantId);
        }
        AssemblyProxyChannel channel = admin ? AssemblyProxyChannel.ADMIN : AssemblyProxyChannel.OWNER;
        byte[] bytes;
        try (InputStream in = documents.open(tenantId, p.documentKey())) {
            bytes = in.readAllBytes();
        } catch (ResourceNotFoundException missing) {
            // P5-C: la referencia existe en PostgreSQL pero el objeto no (fallo parcial o borrado externo): auditado.
            events.deniedAction(tenantId, assemblyId, p.propertyId(), p.id(), actorId, channel, "PROXY_DOCUMENT_MISSING",
                    p.status(), "PROXY_DOCUMENT_MISSING");
            throw new BusinessRuleConflictException("PROXY_DOCUMENT_MISSING",
                    "El documento del poder no está disponible en el almacenamiento. Avisa a la administración.");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        // P5-C: integridad. El contenido debe tener el SHA-256 registrado al subirlo; si no, no se entrega.
        if (!ProxyDocumentInspector.sha256(bytes).equalsIgnoreCase(p.documentSha256())) {
            events.deniedAction(tenantId, assemblyId, p.propertyId(), p.id(), actorId, channel, "PROXY_DOCUMENT_INTEGRITY_FAILED",
                    p.status(), "PROXY_DOCUMENT_INTEGRITY_FAILED");
            throw new BusinessRuleConflictException("PROXY_DOCUMENT_INTEGRITY_FAILED",
                    "El documento almacenado no coincide con el registrado; no se entrega. Avisa a la administración.");
        }
        events.success(tenantId, assemblyId, p.propertyId(), p.id(), actorId,
                admin ? AssemblyProxyChannel.ADMIN : AssemblyProxyChannel.OWNER, "PROXY_DOCUMENT_ACCESSED", null, null);
        return new DocumentContent(p.documentContentType(), bytes);
    }

    // ── reglas comunes ────────────────────────────────────────────────────────

    /** Poder de esta asamblea y comunidad (404 si no, igual que si no existiera) y su unidad bloqueada. */
    private AssemblyProxy lockForChange(UUID assemblyId, UUID proxyId, UUID tenantId) {
        AssemblyProxy p = proxies.find(proxyId, assemblyId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("AssemblyProxy", proxyId));
        lockUnit(p.propertyId(), tenantId);
        return p;
    }

    private void lockUnit(UUID propertyId, UUID tenantId) {
        if (propertyId == null || propertyRepository.lockForVotingRights(propertyId, tenantId).isEmpty()) {
            throw new ResourceNotFoundException("Property", propertyId);
        }
    }

    /** El otorgante, y solo mientras siga siendo propietario activo de la unidad; para cualquier otro, 404. */
    private void requireGrantor(AssemblyProxy p, UUID actorId, UUID tenantId) {
        boolean grantor = actorId != null && actorId.equals(p.grantorUserId())
                && proxies.lockActiveOwnerRelation(p.grantorResidentId(), p.propertyId(), tenantId).isPresent();
        if (!grantor) {
            throw new ResourceNotFoundException("AssemblyProxy", p.id());
        }
    }

    private void requireTransition(AssemblyProxy p, AssemblyProxyStatus target, UUID actorId, AssemblyProxyChannel channel) {
        if (!p.status().canTransitionTo(target)) {
            events.denied(p.tenantId(), p.assemblyId(), p.propertyId(), p.id(), actorId, channel, p.status(), target, "INVALID_PROXY_TRANSITION");
            throw new BusinessRuleConflictException("INVALID_PROXY_TRANSITION",
                    "El poder está en estado " + p.status() + " y no puede pasar a " + target + ".");
        }
    }

    /** La asamblea es de la comunidad (404 si no) y no ha finalizado; FOR SHARE, ultimo bloqueo del orden. */
    private void requireOpenAssembly(UUID assemblyId, UUID tenantId) {
        String status = proxies.lockAssemblyStatus(assemblyId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Assembly", assemblyId));
        if ("ENDED".equals(status)) {
            throw new BusinessRuleConflictException("ASSEMBLY_ENDED", "La asamblea ya finalizó.");
        }
    }

    /**
     * P1-R7 y representante unico (E5): con la unidad bloqueada (la misma fila que toman delegar y registrar asistencia),
     * no hay delegacion de voto ACTIVA (la delegacion es por unidad y vale para todas sus asambleas) ni asistencia ya
     * registrada de la unidad en esta asamblea.
     */
    private void requireNoCompetingRepresentation(UUID assemblyId, UUID propertyId, UUID tenantId) {
        if (proxies.existsActiveDelegation(propertyId, tenantId)) {
            throw new BusinessRuleConflictException("PROXY_CONFLICTS_WITH_DELEGATION",
                    "La unidad tiene una delegación de voto activa; revócala antes de otorgar un poder.");
        }
        if (proxies.existsAttendance(assemblyId, propertyId, tenantId)) {
            throw new BusinessRuleConflictException("PROXY_BLOCKED_BY_ATTENDANCE",
                    "La unidad ya tiene asistencia registrada en esta asamblea.");
        }
    }

    /** Sin efectos parciales: si la transaccion se revierte, el documento subido no queda huerfano en el almacenamiento. */
    private void discardOnRollback(String key) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    documents.discard(key);
                }
            }
        });
    }

    private AssemblyProxyResponse toResponse(AssemblyProxy p) {
        return new AssemblyProxyResponse(p.id(), p.assemblyId(), p.propertyId(), p.grantorResidentId(), p.proxyUserId(),
                p.proxyFullName(), p.proxyDocumentType(), p.proxyDocumentNumber(), p.status().name(), p.createdChannel().name(),
                p.documentContentType(), p.documentSizeBytes(), p.documentRegisteredAt(), p.reviewedAt(), p.reviewDecision(),
                p.rejectionReason(), p.revokedAt(), p.expiredAt(), p.createdAt(),
                // P6: numero de la unidad (el apoderado con cuenta no es residente de ella y debe saber a quien representa).
                propertyRepository.findByIdAndTenantId(p.propertyId(), p.tenantId())
                        .map(com.havita.modules.property.domain.Property::getNumber).orElse(null));
    }
}
