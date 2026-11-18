package com.havita.modules.staff.application;
import com.havita.modules.identity.domain.UserStatus;
import com.havita.modules.identity.domain.MembershipStatus;
import com.havita.modules.staff.domain.StaffMemberStatus;

import com.havita.common.exception.BusinessRuleConflictException;
import com.havita.common.exception.ResourceNotFoundException;
import com.havita.modules.identity.domain.Membership;
import com.havita.modules.identity.domain.RoleType;
import com.havita.modules.identity.domain.User;
import com.havita.modules.identity.infrastructure.MembershipRepository;
import com.havita.modules.identity.infrastructure.UserRepository;
import com.havita.modules.staff.api.dto.CreateStaffMemberRequest;
import com.havita.modules.staff.api.dto.StaffMemberResponse;
import com.havita.modules.staff.domain.StaffMember;
import com.havita.modules.staff.infrastructure.StaffMemberRepository;
import com.havita.security.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class StaffService {

    private final com.havita.security.AccessValidator accessValidator;

    private final StaffMemberRepository staffMemberRepository;
    private final UserRepository userRepository;
    private final MembershipRepository membershipRepository;
    private final PasswordEncoder passwordEncoder;
    private final com.havita.modules.identity.application.GlobalEmailValidationService globalEmailValidationService;
    private final com.havita.modules.identity.application.TenantUserResolver tenantUserResolver;

    @Transactional(readOnly = true)
    public Page<StaffMemberResponse> findAll(String search, Pageable pageable) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        Page<StaffMember> page;

        if (search != null && !search.isBlank()) {
            page = staffMemberRepository.searchStaffMembers(tenantId, search.trim(), pageable);
        } else {
            page = staffMemberRepository.findByTenantId(tenantId, pageable);
        }

        return page.map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public StaffMemberResponse findById(UUID id) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        StaffMember staff = staffMemberRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("StaffMember", id));
        return toResponse(staff);
    }

    @Transactional(readOnly = true)
    public StaffMemberResponse findByUserId(UUID userId) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        StaffMember staff = staffMemberRepository.findByTenantIdAndUserId(tenantId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("StaffMember", "UserId: " + userId));
        return toResponse(staff);
    }

    @Transactional
    public StaffMemberResponse create(CreateStaffMemberRequest request) {
        // SEC-9 (P1): permiso comprobado de nuevo dentro de la transaccion (FOR SHARE), antes de tocar nada.
        accessValidator.lockCommunityAdministrator();
        UUID tenantId = TenantContextHolder.getRequiredTenantId();

        if (staffMemberRepository.existsByTenantIdAndDocumentNumber(tenantId, request.documentNumber().trim())) {
            throw new BusinessRuleConflictException(
                    "STAFF_DOCUMENT_EXISTS",
                    "Ya existe un miembro del personal con el documento '" + request.documentNumber() + "' en esta copropiedad.");
        }

        if (request.email() != null && !request.email().isBlank()) {
            globalEmailValidationService.validateEmailUniqueness(request.email());
        }

        User user = null;
        if (request.userId() != null) {
            user = requireLinkableAccount(request, tenantId);
        } else if (Boolean.TRUE.equals(request.createSystemAccess())) {
            String emailToUse = request.loginEmail() != null && !request.loginEmail().isBlank() 
                    ? request.loginEmail().trim() 
                    : request.email();
                    
            if (emailToUse == null || emailToUse.isBlank()) {
                throw new BusinessRuleConflictException("INVALID_EMAIL", "El correo electrónico es requerido para crear el acceso al sistema.");
            }
            
            // Check uniqueness for the login email if it's different from the staff email we already checked
            if (request.email() == null || request.email().isBlank() || !emailToUse.equalsIgnoreCase(request.email().trim())) {
                globalEmailValidationService.validateEmailUniqueness(emailToUse);
            }
            
            user = User.builder()
                    .email(emailToUse.toLowerCase())
                    .passwordHash(passwordEncoder.encode(request.password()))
                    .fullName(request.firstName().trim() + " " + request.lastName().trim())
                    .phone(request.phone())
                    .status(UserStatus.ACTIVE)
                    .hasCompletedOnboarding(true)
                    .build();
            user = userRepository.save(user);

            RoleType role = RoleType.STAFF;
            if (request.systemRole() != null) {
                try {
                    role = RoleType.valueOf(request.systemRole().toUpperCase());
                } catch (IllegalArgumentException e) {
                    throw new BusinessRuleConflictException("INVALID_ROLE", "El rol " + request.systemRole() + " no es válido.");
                }
                
                // Prevent privilege escalation
                if (role == RoleType.SUPER_ADMIN || role == RoleType.PLATFORM_ADMIN) {
                    throw new org.springframework.security.access.AccessDeniedException("No puedes asignar roles globales desde este módulo.");
                }
                // SEC-9: fuera de la administracion global solo se asignan STAFF o SECURITY (nunca READ_ONLY ni COMMUNITY_ADMIN).
                if (!com.havita.security.AuthorizationScope.isGlobal() && role != RoleType.STAFF && role != RoleType.SECURITY) {
                    throw new org.springframework.security.access.AccessDeniedException("Solo puedes asignar los roles STAFF o SECURITY al personal.");
                }
            }

            Membership membership = Membership.builder()
                    .user(user)
                    .tenantId(tenantId)
                    .role(role)
                    .status(MembershipStatus.ACTIVE)
                    .build();
            membershipRepository.save(membership);
        }

        StaffMember staff = StaffMember.builder()
                .user(user)
                .firstName(request.firstName().trim())
                .lastName(request.lastName().trim())
                .documentType(request.documentType() != null ? request.documentType() : "CC")
                .documentNumber(request.documentNumber().trim())
                .phone(request.phone())
                .email(request.email())
                .position(request.position().trim())
                .area(request.area() != null ? request.area() : "Portería")
                .contractType(request.contractType() != null ? request.contractType() : "Empleado")
                .company(request.company())
                .startDate(request.startDate() != null ? request.startDate() : LocalDate.now())
                .status(StaffMemberStatus.ACTIVE)
                .assignedGate(request.assignedGate())
                .avatarUrl(request.avatarUrl())
                .observations(request.observations())
                .build();
        staff.setTenantId(tenantId);

        staff = staffMemberRepository.save(staff);
        log.info("Personal registrado: id={} (Cargo: {})", staff.getId(), staff.getPosition());
        return toResponse(staff);
    }

    /** Roles con los que una cuenta existente puede figurar como personal (los mismos que asigna la administracion). */
    private static final java.util.Set<RoleType> LINKABLE_STAFF_ROLES = java.util.EnumSet.of(RoleType.STAFF, RoleType.SECURITY);

    /**
     * SEC-9 (P2, SEC-9-7): vincular una cuenta existente a una ficha de personal. Antes se aceptaba cualquier userId
     * (incluidas cuentas de otras comunidades). Ahora:
     * <ul>
     *   <li>la cuenta debe pertenecer a esta comunidad (TenantUserResolver): si no existe o es de otra, 404 sin
     *       distinguir los casos (no permite enumerar cuentas ajenas);</li>
     *   <li>cuenta ACTIVE y membresia ACTIVE en esta comunidad, con rol STAFF o SECURITY;</li>
     *   <li>la cuenta no puede estar ya vinculada a otra ficha de personal de la comunidad;</li>
     *   <li>vincular nunca crea ni cambia membresias ni roles: no se combina con createSystemAccess ni systemRole.</li>
     * </ul>
     * La autorizacion del llamante (administracion de la comunidad) ya se comprobo dentro de la transaccion.
     * Vincular cuentas con otros roles (p. ej. un RESIDENT que tambien trabaja en la comunidad) requiere una decision
     * de negocio y queda rechazado (409).
     */
    private User requireLinkableAccount(CreateStaffMemberRequest request, UUID tenantId) {
        if (Boolean.TRUE.equals(request.createSystemAccess()) || (request.systemRole() != null && !request.systemRole().isBlank())) {
            throw new BusinessRuleConflictException("STAFF_LINK_CONFLICT",
                    "Al vincular una cuenta existente no se puede crear acceso ni asignar un rol.");
        }
        User user = tenantUserResolver.requireUserInTenant(request.userId(), tenantId);
        Membership membership = membershipRepository.findByUserIdAndTenantId(user.getId(), tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("User", request.userId()));
        if (user.getStatus() != UserStatus.ACTIVE || membership.getStatus() != MembershipStatus.ACTIVE
                || !LINKABLE_STAFF_ROLES.contains(membership.getRole())) {
            throw new BusinessRuleConflictException("STAFF_ACCOUNT_NOT_LINKABLE",
                    "La cuenta no puede vincularse como personal: debe estar activa en esta copropiedad con rol STAFF o SECURITY.");
        }
        if (staffMemberRepository.findByTenantIdAndUserId(tenantId, user.getId()).isPresent()) {
            throw new BusinessRuleConflictException("STAFF_ACCOUNT_ALREADY_LINKED",
                    "La cuenta ya está vinculada a otro miembro del personal de esta copropiedad.");
        }
        return user;
    }

    private StaffMemberResponse toResponse(StaffMember s) {
        return new StaffMemberResponse(
                s.getId(),
                s.getTenantId(),
                s.getUser() != null ? s.getUser().getId() : null,
                s.getFirstName(),
                s.getLastName(),
                s.getFullName(),
                s.getDocumentType(),
                s.getDocumentNumber(),
                s.getPhone(),
                s.getEmail(),
                s.getPosition(),
                s.getArea(),
                s.getContractType(),
                s.getCompany(),
                s.getStartDate(),
                s.getStatus(),
                s.getAssignedGate(),
                s.getAvatarUrl(),
                s.getObservations(),
                s.getCreatedAt(),
                s.getUpdatedAt()
        );
    }
}
