package com.havita.modules.resident.application;

import com.havita.modules.resident.domain.ResidentStatus;
import com.havita.modules.identity.domain.MembershipStatus;
import com.havita.modules.identity.domain.UserStatus;
import com.havita.modules.property.domain.PropertyStatus;
import com.havita.common.exception.BusinessRuleConflictException;
import com.havita.common.exception.ResourceNotFoundException;
import com.havita.modules.identity.domain.Membership;
import com.havita.modules.identity.domain.RoleType;
import com.havita.modules.identity.domain.User;
import com.havita.modules.identity.infrastructure.MembershipRepository;
import com.havita.modules.identity.infrastructure.UserRepository;
import com.havita.modules.organization.domain.Tenant;
import com.havita.modules.organization.infrastructure.TenantRepository;
import com.havita.modules.property.domain.Property;
import com.havita.modules.property.infrastructure.PropertyRepository;
import com.havita.modules.resident.api.dto.PublicInvitationResponse;
import com.havita.modules.resident.api.dto.RegisterFromInvitationRequest;
import com.havita.modules.resident.api.dto.ResidentInvitationResponse;
import com.havita.modules.resident.domain.InvitationStatus;
import com.havita.modules.resident.domain.Resident;
import com.havita.modules.resident.domain.ResidentInvitation;
import com.havita.modules.resident.domain.ResidentTypes;
import com.havita.modules.resident.infrastructure.ResidentInvitationRepository;
import com.havita.modules.resident.infrastructure.ResidentRepository;
import com.havita.modules.sync.application.SyncEventPublisher;
import com.havita.modules.sync.domain.SyncEvent;
import com.havita.security.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;



@Slf4j
@Service
@RequiredArgsConstructor
public class ResidentInvitationService {

    private final ResidentInvitationRepository invitationRepository;
    private final PropertyRepository propertyRepository;
    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final MembershipRepository membershipRepository;
    private final ResidentRepository residentRepository;
    private final PasswordEncoder passwordEncoder;
    private final SyncEventPublisher syncEventPublisher;
    private final ResidentLifecycleAudit lifecycleAudit;
    private final com.havita.security.AccessValidator accessValidator;
    private final com.havita.modules.identity.application.GlobalEmailValidationService globalEmailValidationService;
    private final com.havita.modules.identity.application.LegalConsentService legalConsentService;

    // ADMIN: Generate Invitation
    @Transactional
    public ResidentInvitationResponse generateInvitation(UUID propertyId, java.util.List<UUID> additionalPropertyIds) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        Property property = propertyRepository.findByIdAndTenantId(propertyId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Property", propertyId));

        if (property.getStatus() == PropertyStatus.OCCUPIED) {
            throw new com.havita.common.exception.BusinessRuleConflictException(
                "PROPERTY_ALREADY_OCCUPIED",
                "No se puede generar invitacion. El inmueble ya se encuentra ocupado."
            );
        }

        String rawToken = InvitationTokens.generate();
        String tokenHash = InvitationTokens.hash(rawToken);

        ResidentInvitation invitation = ResidentInvitation.builder()
                .property(property)
                .tokenHash(tokenHash)
                .status(InvitationStatus.PENDING)
                .invitationType("OWNER")
                .maxUses(1)
                .usedCount(0)
                .expiresAt(OffsetDateTime.now().plusHours(24))
                .additionalPropertyIds(additionalPropertyIds != null && !additionalPropertyIds.isEmpty() ? additionalPropertyIds.stream().map(UUID::toString).collect(java.util.stream.Collectors.joining(",")) : null)
                .build();
        invitation.setTenantId(tenantId);
        invitation = invitationRepository.save(invitation);

        Tenant tenant = tenantRepository.findById(tenantId).orElseThrow();
        String link = "http://" + tenant.getSubdomain() + ".localhost:5173/register/invite/" + rawToken;

        return toResponse(invitation, link);
    }

    // RESIDENT: Generate Invitation for Household Member
    @Transactional
    public ResidentInvitationResponse generateHouseholdInvitation(UUID propertyId, String inviteeEmail, String inviteeName, String inviteePhone, String type) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        UUID userId = UUID.fromString((String) org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication().getPrincipal());
        
        List<Resident> userResidents = residentRepository.findByUserIdAndTenantId(userId, tenantId);
        // Fase 2 / E1: solo una relacion ACTIVE con la unidad permite invitar (antes valia tambien una revocada).
        com.havita.modules.resident.domain.Resident userRel = userResidents.stream()
            .filter(r -> r.getProperty().getId().equals(propertyId))
            .filter(r -> r.getStatus() == ResidentStatus.ACTIVE)
            .findFirst()
            .orElse(null);

        if (userRel == null) {
            throw new BusinessRuleConflictException("UNAUTHORIZED_PROPERTY", "No tienes permisos para invitar residentes a esta propiedad.");
        }
        
        // P0-C: sin tipo (null o vacio) se mantiene el valor por defecto de siempre (Residente); cualquier otro valor debe
        // ser un tipo valido y, en una invitacion del hogar, Arrendatario o Residente. Un residente no puede crear una
        // invitacion de Propietario (se aceptaria como propietario); la propiedad la asigna la administracion o un traspaso.
        String invType = type != null && !type.isBlank() ? ResidentTypes.canonicalOrNull(type) : ResidentTypes.RESIDENT;
        if (!ResidentTypes.HOUSEHOLD_INVITABLE.contains(invType)) {
            throw new com.havita.common.exception.InvalidRequestException("INVALID_INVITATION_TYPE",
                    "Solo puedes invitar a un Arrendatario o a un Residente.");
        }
        
        if (invType.equalsIgnoreCase("Arrendatario")) {
            if (!userRel.getType().equalsIgnoreCase("Propietario")) {
                throw new BusinessRuleConflictException("UNAUTHORIZED_INVITE", "Solo el propietario puede invitar a un arrendatario.");
            }
            boolean hasActiveTenant = residentRepository.existsByPropertyIdAndTypeIgnoreCaseAndStatus(
                    propertyId, "Arrendatario", ResidentStatus.ACTIVE);
            if (hasActiveTenant) {
                throw new BusinessRuleConflictException("SECOND_ACTIVE_TENANT",
                        "Esta unidad ya tiene un arrendatario activo. Debes remover al arrendatario actual antes de agregar uno nuevo.");
            }
            boolean hasPendingTenantInvite = invitationRepository.existsByPropertyIdAndInvitationTypeIgnoreCaseAndStatus(
                    propertyId, "Arrendatario", InvitationStatus.PENDING);
            if (hasPendingTenantInvite) {
                throw new BusinessRuleConflictException("PENDING_TENANT_INVITATION",
                        "Ya existe una invitación pendiente para un arrendatario en esta unidad. Debes cancelarla antes de generar una nueva.");
            }
        }
        if (invType.equalsIgnoreCase("Residente") && userRel.getType().equalsIgnoreCase("Residente")) {
            throw new BusinessRuleConflictException("UNAUTHORIZED_INVITE", "Un residente adicional no puede invitar a otros residentes.");
        }

        if (inviteeEmail != null && !inviteeEmail.isBlank() && (invType.equalsIgnoreCase("Residente") || invType.equalsIgnoreCase("Arrendatario"))) {
            String cleanEmail = inviteeEmail.trim().toLowerCase();
            List<Resident> activeOtherUnits = residentRepository.findActiveNonOwnerResidences(null, cleanEmail, ResidentStatus.ACTIVE)
                    .stream()
                    .filter(r -> !r.getProperty().getId().equals(propertyId))
                    .toList();
            if (!activeOtherUnits.isEmpty()) {
                // P0-C (R5): la busqueda es global; la unidad solo se nombra si es de esta misma comunidad.
                throw new BusinessRuleConflictException("ALREADY_ACTIVE_IN_ANOTHER_PROPERTY",
                        "El usuario con correo " + inviteeEmail + " ya cuenta con una residencia activa en "
                        + ResidenceConflictMessages.otherUnit(activeOtherUnits.get(0), tenantId)
                        + ". Un residente o arrendatario solo puede tener un apartamento activo a la vez.");
            }
        }

        Property property = propertyRepository.findByIdAndTenantId(propertyId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Property", propertyId));

        String rawToken = InvitationTokens.generate();
        String tokenHash = InvitationTokens.hash(rawToken);

        ResidentInvitation invitation = ResidentInvitation.builder()
                .property(property)
                .tokenHash(tokenHash)
                .status(InvitationStatus.PENDING)
                .invitationType(invType)
                .inviterUserId(userId)
                .inviteeEmail(inviteeEmail)
                .inviteeName(inviteeName)
                .inviteePhone(inviteePhone)
                .maxUses(1)
                .usedCount(0)
                .expiresAt(OffsetDateTime.now().plusHours(24))
                .build();
        invitation.setTenantId(tenantId);
        invitation = invitationRepository.save(invitation);

        Tenant tenant = tenantRepository.findById(tenantId).orElseThrow();
        String link = "http://" + tenant.getSubdomain() + ".localhost:5173/register/invite/" + rawToken;

        return toResponse(invitation, link);
    }

    
    // ADMIN: Generate Transfer Invitation (tied to existing Resident)
    @Transactional
    public ResidentInvitationResponse generateTransferInvitation(UUID propertyId, Resident resident) {
        accessValidator.lockCommunityAdministrator(); // SEC-8/SEC-9: tambien aqui, transaccional
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        Property property = propertyRepository.findByIdAndTenantId(propertyId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Property", propertyId));

        String rawToken = InvitationTokens.generate();
        String tokenHash = InvitationTokens.hash(rawToken);

        ResidentInvitation invitation = ResidentInvitation.builder()
                .property(property)
                .resident(resident)
                .tokenHash(tokenHash)
                .status(InvitationStatus.PENDING)
                .invitationType("Propietario")
                .inviteeEmail(resident.getEmail())
                .inviteeName(resident.getName())
                .inviteePhone(resident.getPhone())
                .maxUses(1)
                .usedCount(0)
                .expiresAt(OffsetDateTime.now().plusHours(48)) // Transfers might take longer
                .build();
        invitation.setTenantId(tenantId);
        invitation = invitationRepository.save(invitation);

        Tenant tenant = tenantRepository.findById(tenantId).orElseThrow();
        String link = "http://" + tenant.getSubdomain() + ".localhost:5173/register/invite/" + rawToken;

        return toResponse(invitation, link);
    }

    // ADMIN: List
    @Transactional(readOnly = true)
    public List<ResidentInvitationResponse> listByProperty(UUID propertyId) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        return invitationRepository.findByTenantIdAndPropertyId(tenantId, propertyId)
                .stream()
                .map(inv -> toResponse(inv, null))
                .collect(Collectors.toList());
    }

    // ADMIN: Revoke
    @Transactional
    public void revokeInvitation(UUID id) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        ResidentInvitation inv = invitationRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("ResidentInvitation", id));
        if (inv.getStatus() == InvitationStatus.PENDING) {
            inv.setStatus(InvitationStatus.REVOKED);
            inv.setRevokedAt(OffsetDateTime.now());
            invitationRepository.save(inv);
        }
    }

    /** Traspaso de titularidad: emitido por la administracion y ligado al registro del nuevo propietario. */
    public static boolean isTransferInvitation(ResidentInvitation inv) {
        return inv.getResident() != null && inv.getInviterUserId() == null && "Propietario".equalsIgnoreCase(inv.getInvitationType());
    }

    /**
     * SEC-8: nuevo enlace para un traspaso pendiente cuyo destinatario aun no se vinculo. Conserva la misma
     * transferencia (mismo registro de propietario) y el mismo destinatario; revoca el enlace anterior y queda auditado.
     * No crea otro propietario ni permite cambiar el correo.
     */
    private ResidentInvitationResponse regenerateTransferInvitation(ResidentInvitation inv) {
        Resident owner = inv.getResident();
        if (owner.getStatus() != ResidentStatus.ACTIVE || owner.getUser() != null
                || !owner.getProperty().getId().equals(inv.getProperty().getId())) {
            throw new BusinessRuleConflictException("TRANSFER_NOT_PENDING",
                    "Este traspaso ya se completo o fue sustituido por otro; no se puede regenerar su enlace.");
        }
        inv.setStatus(InvitationStatus.REVOKED);
        inv.setRevokedAt(OffsetDateTime.now());
        invitationRepository.saveAndFlush(inv);
        ResidentInvitationResponse renewed = generateTransferInvitation(inv.getProperty().getId(), owner);
        lifecycleAudit.transferInvitationRegenerated(owner, inv.getId(), renewed.id());
        return renewed;
    }

    /** SEC-8: al iniciar otra transferencia, los enlaces de traspaso pendientes de esa unidad dejan de valer. */
    @Transactional
    public void revokePendingTransferInvitations(UUID propertyId) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        invitationRepository.findByTenantIdAndPropertyId(tenantId, propertyId).stream()
                .filter(i -> i.getStatus() == InvitationStatus.PENDING && isTransferInvitation(i))
                .forEach(i -> {
                    i.setStatus(InvitationStatus.REVOKED);
                    i.setRevokedAt(OffsetDateTime.now());
                    invitationRepository.save(i);
                });
    }

    // ADMIN: Regenerate. El enlace solo se entrega al emitir la invitacion; para volver a compartirla se revoca la
    // pendiente y se emite otra para la misma unidad. Todo en una transaccion: si la emision falla (p. ej. la unidad
    // ya esta ocupada) la original sigue pendiente.
    @Transactional
    public ResidentInvitationResponse regenerateInvitation(UUID id) {
        accessValidator.lockCommunityAdministrator();
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        ResidentInvitation inv = invitationRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("ResidentInvitation", id));
        boolean transfer = isTransferInvitation(inv);
        if (!"OWNER".equals(inv.getInvitationType()) && !transfer) {
            throw new BusinessRuleConflictException("INVITATION_NOT_REGENERABLE",
                    "Solo se pueden regenerar las invitaciones emitidas por la administracion.");
        }
        if (inv.getStatus() != InvitationStatus.PENDING) {
            throw new BusinessRuleConflictException("INVITATION_NOT_PENDING", "Solo se puede regenerar una invitacion pendiente.");
        }
        if (transfer) {
            return regenerateTransferInvitation(inv);
        }

        inv.setStatus(InvitationStatus.REVOKED);
        inv.setRevokedAt(OffsetDateTime.now());
        invitationRepository.saveAndFlush(inv);

        List<UUID> additionalIds = inv.getAdditionalPropertyIds() == null || inv.getAdditionalPropertyIds().isBlank()
                ? List.of()
                : java.util.Arrays.stream(inv.getAdditionalPropertyIds().split(",")).map(String::trim).map(UUID::fromString).toList();
        return generateInvitation(inv.getProperty().getId(), additionalIds);
    }

    // PUBLIC: Validate
    @Transactional(readOnly = true)
    public PublicInvitationResponse validatePublicToken(String rawToken) {
        ResidentInvitation inv = getValidInvitationByToken(rawToken);
        Tenant tenant = tenantRepository.findById(inv.getTenantId()).orElseThrow();
        return new PublicInvitationResponse(
                tenant.getName(),
                inv.getProperty().getName(),
                inv.getProperty().getStructure() != null ? inv.getProperty().getStructure().getName() : "",
                inv.getInvitationType()
        );
    }

    // PUBLIC: Register
    @Transactional
    public void registerResident(String rawToken, RegisterFromInvitationRequest request) {
        registerResident(rawToken, request, null);
    }

    @Transactional
    public void registerResident(String rawToken, RegisterFromInvitationRequest request, jakarta.servlet.http.HttpServletRequest httpRequest) {
        if (request.acceptTerms() != null && !request.acceptTerms()) {
            throw new BusinessRuleConflictException("TERMS_REQUIRED", "Debes aceptar los Términos de Servicio.");
        }
        if (request.acceptPrivacy() != null && !request.acceptPrivacy()) {
            throw new BusinessRuleConflictException("PRIVACY_REQUIRED", "Debes aceptar la Política de Privacidad.");
        }

        // SEC-7: bloqueo de la invitacion hasta el commit; una segunda aceptacion concurrente espera y la ve USED.
        ResidentInvitation inv = lockValidInvitationByToken(rawToken);
        
        // Setup Tenant Context for this operation (so entities save correctly)
        TenantContextHolder.setTenantId(inv.getTenantId());
        try {
            String cleanEmail = request.email().toLowerCase().trim();
            assertIntendedRecipient(inv, cleanEmail);
            String targetType = inv.getInvitationType().equals("OWNER") || inv.getInvitationType().equalsIgnoreCase("Propietario")
                    ? "Propietario" : (inv.getInvitationType().equalsIgnoreCase("Arrendatario") ? "Arrendatario" : "Residente");

            // 1. Resolve or create global User
            Optional<User> existingUserOpt = userRepository.findByEmailIgnoreCase(cleanEmail);
            User user;

            if (existingUserOpt.isPresent()) {
                user = existingUserOpt.get();
                // Validate password against existing account
                if (request.password() == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
                    throw new BusinessRuleConflictException("INVALID_CREDENTIALS",
                            "Ya existe una cuenta en Havita con este correo electrónico. Por favor ingresa la contraseña correcta de tu cuenta para vincularte a esta unidad.");
                }
                // Cierre SEC-9 (R2, regla de rol unico): una cuenta con rol global de la plataforma no recibe ademas una
                // membresia RESIDENT (seria un segundo rol); se rechaza explicando el motivo, sin modificar nada. Se
                // comprueba despues de la contrasena para no revelar el tipo de cuenta a quien no la conoce.
                boolean globalAccount = membershipRepository.findByUserId(user.getId()).stream()
                        .anyMatch(m -> m.getTenantId() == null && (m.getRole() == RoleType.SUPER_ADMIN
                                || m.getRole() == RoleType.PLATFORM_ADMIN || m.getRole() == RoleType.READ_ONLY));
                if (globalAccount) {
                    throw new BusinessRuleConflictException("GLOBAL_ACCOUNT_CANNOT_BE_RESIDENT",
                            "Esta cuenta tiene un rol de administración de la plataforma y no puede vincularse como residente. "
                                    + "Usa una cuenta diferente para tu unidad.");
                }
                if ((user.getPhone() == null || user.getPhone().isBlank()) && request.phone() != null) {
                    user.setPhone(request.phone());
                    userRepository.save(user);
                }
            } else {
                // El registro de residente ligado a la invitacion (traspaso) es el propio destinatario, no un duplicado.
                globalEmailValidationService.validateEmailUniqueness(cleanEmail, null,
                        inv.getResident() != null ? inv.getResident().getId() : null, null);
                user = User.builder().status(UserStatus.ACTIVE)
                        .email(cleanEmail)
                        .passwordHash(passwordEncoder.encode(request.password()))
                        .fullName(request.name().trim())
                        .phone(request.phone())
                        .hasCompletedOnboarding(false)
                        .build();
                user = userRepository.save(user);
            }

            // 2. Ensure Membership in this tenant
            final User finalUser = user;
            final UUID finalUserId = user.getId();
            boolean hasMembership = membershipRepository.findByUserIdAndTenantId(finalUserId, inv.getTenantId()).isPresent();
            if (!hasMembership) {
                Membership membership = new Membership();
                membership.setUser(finalUser);
                membership.setTenantId(inv.getTenantId());
                membership.setRole(RoleType.RESIDENT);
                membership.setStatus(MembershipStatus.ACTIVE);
                membershipRepository.save(membership);
            }

            // 3. Regla 1: MÁXIMO UN ARRENDATARIO ACTIVO POR UNIDAD
            if (targetType.equalsIgnoreCase("Arrendatario")) {
                boolean hasActiveTenant = residentRepository.existsByPropertyIdAndTypeIgnoreCaseAndStatus(
                        inv.getProperty().getId(), "Arrendatario", ResidentStatus.ACTIVE);
                if (hasActiveTenant) {
                    throw new BusinessRuleConflictException("SECOND_ACTIVE_TENANT",
                            "Esta unidad ya tiene un arrendatario activo. La invitación no puede ser procesada.");
                }
            }

            // 4. Regla 2: UN RESIDENTE O ARRENDATARIO SOLO PUEDE TENER 1 APTO ACTIVO A LA VEZ
            if (targetType.equalsIgnoreCase("Residente") || targetType.equalsIgnoreCase("Arrendatario")) {
                List<Resident> activeOtherUnits = residentRepository.findActiveNonOwnerResidences(finalUserId, cleanEmail, ResidentStatus.ACTIVE)
                        .stream()
                        .filter(r -> !r.getProperty().getId().equals(inv.getProperty().getId()))
                        .toList();

                if (!activeOtherUnits.isEmpty()) {
                    // P0-C (R5): la busqueda es global; la unidad solo se nombra si es de la comunidad de la invitacion.
                    throw new BusinessRuleConflictException("ALREADY_ACTIVE_IN_ANOTHER_PROPERTY",
                            "El usuario ya cuenta con una residencia activa en "
                            + ResidenceConflictMessages.otherUnit(activeOtherUnits.get(0), inv.getTenantId())
                            + ". Un residente o arrendatario solo puede tener un apartamento activo a la vez.");
                }
            }

            // 5. Parent Resident & Granted By
            Resident parentResident = null;
            User grantedByUser = null;
            if (inv.getInviterUserId() != null) {
                grantedByUser = userRepository.findById(inv.getInviterUserId()).orElse(null);
                parentResident = residentRepository.findByUserIdAndTenantId(inv.getInviterUserId(), inv.getTenantId()).stream()
                        .filter(r -> r.getProperty().getId().equals(inv.getProperty().getId()) && r.getStatus() == ResidentStatus.ACTIVE)
                        .findFirst()
                        .orElse(null);
            }

            // 6. Link or create Resident in this property
            if (inv.getResident() != null) {
                Resident existing = inv.getResident();
                // SEC-8: un traspaso sustituido por otra transferencia no reactiva a su antiguo destinatario.
                if (isTransferInvitation(inv) && existing.getStatus() != ResidentStatus.ACTIVE) {
                    throw new BusinessRuleConflictException("TRANSFER_SUPERSEDED",
                            "Esta transferencia ya no esta vigente. Solicita un nuevo enlace a la administracion.");
                }
                authorizeReactivation(existing, inv);
                existing.setUser(finalUser);
                existing.setStatus(ResidentStatus.ACTIVE);
                existing.setEndedAt(null);
                existing.setEndedBy(null);
                existing.setEndReason(null);
                residentRepository.save(existing);
            } else {
                List<Resident> existingInProperty = residentRepository.findByTenantIdAndPropertyId(inv.getTenantId(), inv.getProperty().getId())
                        .stream()
                        .filter(r -> (r.getEmail() != null && r.getEmail().equalsIgnoreCase(cleanEmail))
                                  || (r.getUser() != null && r.getUser().getId().equals(finalUserId)))
                        .toList();

                if (!existingInProperty.isEmpty()) {
                    // Reactivar registro existente en esta propiedad
                    Resident existing = existingInProperty.get(0);
                    authorizeReactivation(existing, inv);
                    existing.setStatus(ResidentStatus.ACTIVE);
                    existing.setType(targetType);
                    existing.setUser(user);
                    existing.setName(request.name().trim());
                    if (request.documentId() != null && !request.documentId().isBlank()) {
                        existing.setDocumentType(request.documentType());
                        existing.setDocumentId(request.documentId().trim());
                    }
                    if (request.phone() != null && !request.phone().isBlank()) {
                        existing.setPhone(request.phone());
                    }
                    existing.setParentResident(parentResident);
                    existing.setGrantedBy(grantedByUser);
                    existing.setEndedAt(null);
                    existing.setEndedBy(null);
                    existing.setEndReason(null);
                    residentRepository.save(existing);
                } else {
                    Resident resident = Resident.builder()
                            .status(ResidentStatus.ACTIVE)
                            .property(inv.getProperty())
                            .user(user)
                            .name(request.name().trim())
                            .documentType(request.documentType())
                            .documentId(request.documentId().trim())
                            .email(cleanEmail)
                            .phone(request.phone())
                            .type(targetType)
                            .parentResident(parentResident)
                            .grantedBy(grantedByUser)
                            .build();
                    resident.setTenantId(inv.getTenantId());
                    residentRepository.save(resident);
                }
            }

            
            // Update Property status to OCCUPIED
            Property prop = inv.getProperty();
            if (prop.getStatus() != PropertyStatus.OCCUPIED) {
                prop.setStatus(PropertyStatus.OCCUPIED);
                propertyRepository.save(prop);
            }
            
            // Handle additional properties
            if (inv.getAdditionalPropertyIds() != null && !inv.getAdditionalPropertyIds().isBlank()) {
                String[] extraIds = inv.getAdditionalPropertyIds().split(",");
                for (String extraId : extraIds) {
                    try {
                        UUID pId = UUID.fromString(extraId.trim());
                        Property extraProp = propertyRepository.findByIdAndTenantId(pId, inv.getTenantId()).orElse(null);
                        if (extraProp != null) {
                            boolean extraExists = residentRepository.findByTenantIdAndPropertyId(inv.getTenantId(), pId)
                                .stream().anyMatch(r -> r.getEmail() != null && r.getEmail().equalsIgnoreCase(request.email()));
                            
                            if (!extraExists) {
                                Resident extraRes = Resident.builder().status(ResidentStatus.ACTIVE)
                                        .property(extraProp)
                                        .user(user)
                                        .name(request.name().trim())
                                        .documentType(request.documentType())
                                        .documentId(request.documentId().trim())
                                        .email(request.email().toLowerCase().trim())
                                        .phone(request.phone())
                                        .type(inv.getInvitationType().equals("OWNER") ? "Propietario" : (inv.getInvitationType().equalsIgnoreCase("Arrendatario") ? "Arrendatario" : "Residente"))
                                        .status(ResidentStatus.ACTIVE)
                                        .build();
                                extraRes.setTenantId(inv.getTenantId());
                                residentRepository.save(extraRes);
                            }
                            
                            if (extraProp.getStatus() != PropertyStatus.OCCUPIED) {
                                extraProp.setStatus(PropertyStatus.OCCUPIED);
                                propertyRepository.save(extraProp);
                            }
                        }
                    } catch (Exception e) {
                        log.warn("Failed to process additional property ID: {}", extraId, e);
                    }
                }
            }

            // Mark invitation as used

            inv.setUsedCount(inv.getUsedCount() + 1);
            if (inv.getUsedCount() >= inv.getMaxUses()) {
                inv.setStatus(InvitationStatus.USED);
            }
            inv.setUsedAt(OffsetDateTime.now());
            invitationRepository.save(inv);
            
            // Publish Domain Events. Listener will broadcast via SSE AFTER_COMMIT
            syncEventPublisher.publish(new SyncEvent("RESIDENT_REGISTERED", inv.getTenantId(), Map.of(
                    "propertyId", prop.getId().toString()
            )));
            syncEventPublisher.publish(new SyncEvent("PROPERTY_OCCUPANCY_CHANGED", inv.getTenantId(), Map.of(
                    "propertyId", prop.getId().toString()
            )));

            // 7. Guardar aceptación de Términos de Servicio y Política de Privacidad (tabla user_legal_consents)
            String ipAddress = null;
            String userAgent = null;
            if (httpRequest != null) {
                String xForwardedFor = httpRequest.getHeader("X-Forwarded-For");
                if (xForwardedFor != null && !xForwardedFor.isBlank()) {
                    ipAddress = xForwardedFor.split(",")[0].trim();
                } else {
                    ipAddress = httpRequest.getRemoteAddr();
                }
                userAgent = httpRequest.getHeader("User-Agent");
                if (userAgent != null && userAgent.length() > 500) {
                    userAgent = userAgent.substring(0, 500);
                }
            }

            if (Boolean.TRUE.equals(request.acceptTerms()) || request.acceptTerms() == null) {
                legalConsentService.recordConsent(
                        finalUserId,
                        inv.getTenantId(),
                        com.havita.modules.identity.domain.LegalDocumentType.TERMS_OF_SERVICE,
                        "1.0",
                        ipAddress,
                        userAgent
                );
            }
            if (Boolean.TRUE.equals(request.acceptPrivacy()) || request.acceptPrivacy() == null) {
                legalConsentService.recordConsent(
                        finalUserId,
                        inv.getTenantId(),
                        com.havita.modules.identity.domain.LegalDocumentType.PRIVACY_POLICY,
                        "1.0",
                        ipAddress,
                        userAgent
                );
            }

        } finally {
            TenantContextHolder.clear();
        }
    }

    private ResidentInvitation getValidInvitationByToken(String rawToken) {
        return ensureUsable(rawToken == null || rawToken.isBlank() ? Optional.empty()
                : invitationRepository.findByTokenHash(InvitationTokens.hash(rawToken)));
    }

    private ResidentInvitation lockValidInvitationByToken(String rawToken) {
        return ensureUsable(rawToken == null || rawToken.isBlank() ? Optional.empty()
                : invitationRepository.findByTokenHashForUpdate(InvitationTokens.hash(rawToken)));
    }

    private ResidentInvitation ensureUsable(Optional<ResidentInvitation> found) {
        ResidentInvitation inv = found
                .orElseThrow(() -> new BusinessRuleConflictException("INVITATION_NOT_FOUND", "Invitacion no valida."));

        if (inv.getStatus() == InvitationStatus.EXPIRED || (inv.getExpiresAt() != null && inv.getExpiresAt().isBefore(OffsetDateTime.now()))) {
            if (inv.getStatus() != InvitationStatus.EXPIRED) {
                inv.setStatus(InvitationStatus.EXPIRED);
                invitationRepository.save(inv);
            }
            throw new BusinessRuleConflictException("INVITATION_EXPIRED", "Invitacion expirada.");
        }
        if (inv.getStatus() == InvitationStatus.REVOKED) {
            throw new BusinessRuleConflictException("INVITATION_REVOKED", "Invitacion revocada.");
        }
        if (inv.getStatus() == InvitationStatus.USED || inv.getUsedCount() >= inv.getMaxUses()) {
            throw new BusinessRuleConflictException("INVITATION_USED", "Invitacion ya utilizada.");
        }
        return inv;
    }

    private ResidentInvitationResponse toResponse(ResidentInvitation inv, String link) {
        return new ResidentInvitationResponse(
                inv.getId(),
                inv.getProperty().getId(),
                inv.getProperty().getName(),
                inv.getStatus(),
                inv.getInvitationType(),
                inv.getMaxUses(),
                inv.getUsedCount(),
                inv.getExpiresAt(),
                inv.getCreatedAt(),
                inv.getUsedAt(),
                link
        );
    }

    /**
     * SEC-7: conocer el token no basta para asumir la identidad del destinatario. Si la invitacion nombra a una persona
     * (cuenta ya ligada al registro de residente, correo del invitado o correo del registro, como en los traspasos),
     * solo esa persona puede usarla. Los enlaces sin destinatario (OWNER de la administracion y los de Mi Hogar sin
     * correo) son portables por diseno: quien los recibe del emisor se registra con su propia cuenta. El mensaje no
     * revela el correo esperado.
     */
    private void assertIntendedRecipient(ResidentInvitation inv, String cleanEmail) {
        Resident bound = inv.getResident();
        String expected = null;
        if (bound != null && bound.getUser() != null) {
            expected = bound.getUser().getEmail();
        } else if (inv.getInviteeEmail() != null && !inv.getInviteeEmail().isBlank()) {
            expected = inv.getInviteeEmail();
        } else if (bound != null && bound.getEmail() != null && !bound.getEmail().isBlank()) {
            expected = bound.getEmail();
        }
        if (expected != null && !expected.trim().equalsIgnoreCase(cleanEmail)) {
            throw new BusinessRuleConflictException("INVITATION_RECIPIENT_MISMATCH",
                    "Esta invitacion esta dirigida a otra persona. Usa el correo electronico al que fue enviada.");
        }
    }

    /** Invitaciones de hogar: las emite un residente desde Mi Hogar (no las OWNER de administracion ni los traspasos). */
    public static boolean isHouseholdInvitation(ResidentInvitation inv) {
        return inv.getInviterUserId() != null && inv.getResident() == null && !"OWNER".equals(inv.getInvitationType());
    }

    /**
     * SEC-7: quien puede cancelar desde Mi Hogar. El emisor, las suyas; el Propietario de la unidad, las de hogar de su
     * unidad. Las OWNER y de traspaso solo las gestiona la administracion.
     */
    public static boolean canCancelFromHome(ResidentInvitation inv, UUID userId, String callerType) {
        return isHouseholdInvitation(inv)
                && (userId.equals(inv.getInviterUserId()) || "Propietario".equalsIgnoreCase(callerType));
    }

    private String activeTypeIn(UUID userId, UUID tenantId, UUID propertyId) {
        return residentRepository.findByUserIdAndTenantId(userId, tenantId).stream()
                .filter(r -> r.getProperty().getId().equals(propertyId) && r.getStatus() == ResidentStatus.ACTIVE)
                .map(Resident::getType)
                .findFirst().orElse(null);
    }

    private ResidentInvitation findInProperty(UUID invitationId, UUID propertyId) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        return invitationRepository.findByIdAndTenantId(invitationId, tenantId)
                .filter(i -> i.getProperty().getId().equals(propertyId))
                .orElseThrow(() -> new ResourceNotFoundException("ResidentInvitation", invitationId));
    }

    @Transactional
    public void cancelInvitation(UUID invitationId, UUID propertyId, UUID userId) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        ResidentInvitation inv = findInProperty(invitationId, propertyId);
        if (!canCancelFromHome(inv, userId, activeTypeIn(userId, tenantId, propertyId))) {
            throw new org.springframework.security.access.AccessDeniedException("No tienes permisos para cancelar esta invitación.");
        }
        if (inv.getStatus() == InvitationStatus.PENDING) {
            inv.setStatus(InvitationStatus.REVOKED);
            inv.setRevokedAt(OffsetDateTime.now());
            invitationRepository.save(inv);
        }
    }

    /**
     * SEC-7: el enlace solo existe al emitirlo. Para volver a compartir una invitacion de hogar pendiente, su emisor
     * (y solo el) la regenera: se revoca la actual y se emite otra con los mismos datos, aplicando de nuevo las reglas
     * de emision. Todo en una transaccion.
     */
    @Transactional
    public ResidentInvitationResponse regenerateHouseholdInvitation(UUID invitationId, UUID propertyId, UUID userId) {
        ResidentInvitation inv = findInProperty(invitationId, propertyId);
        if (!isHouseholdInvitation(inv) || !userId.equals(inv.getInviterUserId())) {
            throw new org.springframework.security.access.AccessDeniedException("Solo quien emitio la invitacion puede generar un nuevo enlace.");
        }
        if (inv.getStatus() != InvitationStatus.PENDING) {
            throw new BusinessRuleConflictException("INVITATION_NOT_PENDING", "Solo se puede regenerar una invitacion pendiente.");
        }
        inv.setStatus(InvitationStatus.REVOKED);
        inv.setRevokedAt(OffsetDateTime.now());
        invitationRepository.saveAndFlush(inv);
        return generateHouseholdInvitation(propertyId, inv.getInviteeEmail(), inv.getInviteeName(), inv.getInviteePhone(),
                inv.getInvitationType());
    }

    /**
     * D-3: una relacion dada de baja por la administracion (ADMIN_REMOVAL) solo se reactiva con autorizacion
     * administrativa. Las invitaciones de la administracion (sin emisor o emitidas por un admin de la comunidad)
     * cuentan como esa autorizacion y quedan auditadas; las de un residente se rechazan.
     */
    private void authorizeReactivation(Resident existing, ResidentInvitation inv) {
        if (existing.getStatus() != ResidentStatus.INACTIVE
                || !ResidentService.ADMIN_REMOVAL.equals(existing.getEndReason())) {
            if (existing.getStatus() == ResidentStatus.INACTIVE) {
                lifecycleAudit.relationReactivated(existing, "INVITATION");
            }
            return;
        }
        boolean issuedByAdministration = inv.getInviterUserId() == null
                || membershipRepository.findByUserIdAndTenantId(inv.getInviterUserId(), inv.getTenantId())
                        .filter(m -> m.getStatus() == MembershipStatus.ACTIVE)
                        .map(m -> m.getRole() == RoleType.COMMUNITY_ADMIN || m.getRole() == RoleType.SUPER_ADMIN)
                        .orElse(false);
        if (!issuedByAdministration) {
            lifecycleAudit.reactivationDenied(existing, "RESIDENT_INVITATION");
            throw new BusinessRuleConflictException("REACTIVATION_REQUIRES_ADMIN",
                    "Tu acceso a esta unidad fue retirado por la administración. Solicita a la administración que lo restablezca.");
        }
        lifecycleAudit.relationReactivated(existing, "ADMIN_INVITATION");
    }
}
