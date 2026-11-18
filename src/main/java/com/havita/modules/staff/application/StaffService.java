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
    /** Al cambiar la contrasena o el correo de acceso se cierran las sesiones abiertas de esa cuenta. */
    private final com.havita.modules.identity.infrastructure.RefreshTokenRepository refreshTokenRepository;

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

    /**
     * Activa o desactiva a un colaborador de la comunidad actual y, si tiene cuenta, su membresia de personal en ESTA
     * comunidad. Antes la pantalla solo lo guardaba en el navegador: un portero «suspendido» conservaba su acceso.
     *
     * <ul>
     *   <li>Solo se toca la membresia de esta comunidad y solo si es de personal (STAFF / SECURITY): nunca el usuario
     *       global, otras comunidades ni un rol de administracion.</li>
     *   <li>Al guardar la membresia, {@code AccessChangeEntityListener} publica {@code AccessChangedEvent}; tras el
     *       commit se cierran sus SSE, se liberan sus chats, se desactivan sus avisos push y, si no le queda ningun
     *       acceso, se revocan sus sesiones. La autorizacion se decide en cada peticion contra PostgreSQL.</li>
     *   <li>Reactivar solo devuelve el acceso si la cuenta del usuario sigue ACTIVE.</li>
     * </ul>
     */
    @Transactional
    public StaffMemberResponse updateStatus(UUID id, StaffMemberStatus newStatus) {
        // SEC-9 (P1): permiso comprobado de nuevo dentro de la transaccion (FOR SHARE), como al crear.
        accessValidator.lockCommunityAdministrator();
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        StaffMember staff = staffMemberRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("StaffMember", id));

        staff.setStatus(newStatus);
        staff = staffMemberRepository.save(staff);

        User user = staff.getUser();
        if (user != null) {
            membershipRepository.findByUserIdAndTenantId(user.getId(), tenantId)
                    .filter(m -> LINKABLE_STAFF_ROLES.contains(m.getRole()))
                    .ifPresent(m -> {
                        MembershipStatus target = newStatus == StaffMemberStatus.ACTIVE && user.getStatus() == UserStatus.ACTIVE
                                ? MembershipStatus.ACTIVE : MembershipStatus.INACTIVE;
                        if (m.getStatus() != target) {
                            m.setStatus(target);
                            membershipRepository.save(m);
                        }
                    });
        }
        log.info("Colaborador {} -> {} (acceso {})", staff.getId(), newStatus, user != null ? "actualizado" : "sin cuenta");
        return toResponse(staff);
    }

    /**
     * Edita los datos del colaborador (no su acceso ni su estado). Antes la pantalla solo los guardaba en el navegador.
     * El documento sigue siendo unico en la comunidad; el correo de contacto se valida igual que al crear.
     */
    @Transactional
    public StaffMemberResponse update(UUID id, com.havita.modules.staff.api.dto.UpdateStaffMemberRequest request) {
        accessValidator.lockCommunityAdministrator();
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        StaffMember staff = staffMemberRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("StaffMember", id));

        String document = request.documentNumber().trim();
        if (!document.equals(staff.getDocumentNumber())
                && staffMemberRepository.existsByTenantIdAndDocumentNumber(tenantId, document)) {
            throw new BusinessRuleConflictException("STAFF_DOCUMENT_EXISTS",
                    "Ya existe un miembro del personal con el documento '" + document + "' en esta copropiedad.");
        }
        String email = request.email() == null || request.email().isBlank() ? null : request.email().trim();
        if (email != null && (staff.getEmail() == null || !email.equalsIgnoreCase(staff.getEmail()))) {
            // Se excluyen el propio colaborador y su cuenta (si no, su propio correo cuenta como duplicado).
            globalEmailValidationService.validateEmailUniqueness(email,
                    staff.getUser() != null ? staff.getUser().getId() : null, null, staff.getId());
        }

        staff.setFirstName(request.firstName().trim());
        staff.setLastName(request.lastName().trim());
        if (request.documentType() != null && !request.documentType().isBlank()) staff.setDocumentType(request.documentType().trim());
        staff.setDocumentNumber(document);
        staff.setPhone(request.phone());
        staff.setEmail(email);
        staff.setPosition(request.position().trim());
        if (request.area() != null && !request.area().isBlank()) staff.setArea(request.area().trim());
        if (request.contractType() != null && !request.contractType().isBlank()) staff.setContractType(request.contractType().trim());
        staff.setCompany(request.company());
        if (request.startDate() != null) staff.setStartDate(request.startDate());
        staff.setAssignedGate(request.assignedGate());
        staff.setObservations(request.observations());
        staff = staffMemberRepository.save(staff);
        log.info("Personal actualizado: id={}", staff.getId());
        return toResponse(staff);
    }

    /**
     * Gestiona el acceso al sistema del colaborador en ESTA comunidad. Antes la pantalla solo lo guardaba en el navegador
     * (incluida la contrasena, en texto plano).
     *
     * <ul>
     *   <li><b>Activar sin cuenta:</b> crea la cuenta (correo unico y contrasena obligatorios) y su membresia de personal.</li>
     *   <li><b>Activar con cuenta:</b> rol STAFF o SECURITY (nunca administracion ni roles globales); la membresia solo
     *       queda ACTIVE si el colaborador y la cuenta estan activos.</li>
     *   <li><b>Desactivar:</b> membresia INACTIVE en esta comunidad (se cierran sus SSE, chats y push; ver
     *       {@code AccessChangedEvent}). La cuenta no se borra.</li>
     *   <li><b>Correo y contrasena</b> solo se cambian si la cuenta no tiene acceso en otra comunidad: la administracion
     *       de una comunidad no puede tomar el control de una cuenta que tambien usa otra. Tras cambiarlos se cierran sus
     *       sesiones.</li>
     *   <li>Una cuenta cuya membresia aqui no es de personal (p. ej. administracion) no se gestiona desde este modulo.</li>
     * </ul>
     */
    @Transactional
    public StaffMemberResponse updateAccess(UUID id, com.havita.modules.staff.api.dto.UpdateStaffAccessRequest request) {
        accessValidator.lockCommunityAdministrator();
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        StaffMember staff = staffMemberRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("StaffMember", id));
        User user = staff.getUser();
        String loginEmail = request.loginEmail() == null || request.loginEmail().isBlank()
                ? null : request.loginEmail().trim().toLowerCase();
        String password = request.password() == null || request.password().isBlank() ? null : request.password();

        if (!Boolean.TRUE.equals(request.enabled())) {
            if (user != null) {
                staffMembership(user, tenantId).ifPresent(m -> {
                    if (m.getStatus() != MembershipStatus.INACTIVE) {
                        m.setStatus(MembershipStatus.INACTIVE);
                        membershipRepository.save(m);
                    }
                });
            }
            log.info("Acceso del colaborador {} desactivado", staff.getId());
            return toResponse(staff);
        }

        RoleType role = resolveStaffRole(request.systemRole());
        if (user == null) {
            String email = loginEmail != null ? loginEmail : (staff.getEmail() != null ? staff.getEmail().trim().toLowerCase() : null);
            if (email == null || email.isBlank()) {
                throw new BusinessRuleConflictException("INVALID_EMAIL", "El correo electrónico es requerido para crear el acceso al sistema.");
            }
            if (password == null) {
                throw new BusinessRuleConflictException("PASSWORD_REQUIRED", "La contraseña es obligatoria para crear el acceso al sistema.");
            }
            // El correo de contacto del propio colaborador puede ser su correo de acceso.
            globalEmailValidationService.validateEmailUniqueness(email, null, null, staff.getId());
            user = userRepository.save(User.builder()
                    .email(email)
                    .passwordHash(passwordEncoder.encode(password))
                    .fullName(staff.getFirstName() + " " + staff.getLastName())
                    .phone(staff.getPhone())
                    .status(UserStatus.ACTIVE)
                    .hasCompletedOnboarding(true)
                    .build());
            staff.setUser(user);
            staff = staffMemberRepository.save(staff);
        } else if (loginEmail != null && !loginEmail.equalsIgnoreCase(user.getEmail()) || password != null) {
            requireExclusiveAccount(user, tenantId);
            if (loginEmail != null && !loginEmail.equalsIgnoreCase(user.getEmail())) {
                globalEmailValidationService.validateEmailUniqueness(loginEmail, user.getId(), null, staff.getId());
                user.setEmail(loginEmail);
            }
            if (password != null) user.setPasswordHash(passwordEncoder.encode(password));
            user = userRepository.save(user);
            refreshTokenRepository.revokeAllByUserId(user.getId());
        }

        final User account = user;
        Membership membership = membershipRepository.findByUserIdAndTenantId(account.getId(), tenantId).orElse(null);
        if (membership != null && !LINKABLE_STAFF_ROLES.contains(membership.getRole())) {
            throw new BusinessRuleConflictException("STAFF_ACCOUNT_NOT_MANAGEABLE",
                    "Esta cuenta tiene otro rol en la comunidad y no se gestiona desde Personal.");
        }
        MembershipStatus target = staff.getStatus() == StaffMemberStatus.ACTIVE && account.getStatus() == UserStatus.ACTIVE
                ? MembershipStatus.ACTIVE : MembershipStatus.INACTIVE;
        if (membership == null) {
            membership = Membership.builder().user(account).tenantId(tenantId).role(role).status(target).build();
            membershipRepository.save(membership);
        } else if (membership.getRole() != role || membership.getStatus() != target) {
            membership.setRole(role);
            membership.setStatus(target);
            membershipRepository.save(membership);
        }
        log.info("Acceso del colaborador {} activo con rol {}", staff.getId(), role);
        return toResponse(staff);
    }

    /** Solo STAFF o SECURITY (por defecto STAFF). Nunca administracion ni roles globales. */
    private RoleType resolveStaffRole(String raw) {
        if (raw == null || raw.isBlank()) return RoleType.STAFF;
        try {
            RoleType role = RoleType.valueOf(raw.trim().toUpperCase());
            if (LINKABLE_STAFF_ROLES.contains(role)) return role;
        } catch (IllegalArgumentException ignored) {
            // cae al error de abajo
        }
        throw new org.springframework.security.access.AccessDeniedException("Solo puedes asignar los roles STAFF o SECURITY al personal.");
    }

    private java.util.Optional<Membership> staffMembership(User user, UUID tenantId) {
        return membershipRepository.findByUserIdAndTenantId(user.getId(), tenantId)
                .filter(m -> LINKABLE_STAFF_ROLES.contains(m.getRole()));
    }

    /** El correo y la contrasena solo se cambian si la cuenta no tiene ninguna membresia en otra comunidad. */
    private void requireExclusiveAccount(User user, UUID tenantId) {
        boolean shared = membershipRepository.findByUserId(user.getId()).stream()
                .anyMatch(m -> !tenantId.equals(m.getTenantId()));
        if (shared) {
            throw new BusinessRuleConflictException("STAFF_ACCOUNT_SHARED",
                    "La cuenta también tiene acceso en otra comunidad: su correo y su contraseña solo los puede cambiar su titular.");
        }
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
                s.getUpdatedAt(),
                systemAccess(s)
        );
    }

    /** Acceso en ESTA comunidad (sin contrasena). Solo cuentas con membresia de personal aqui. */
    private StaffMemberResponse.SystemAccess systemAccess(StaffMember s) {
        User user = s.getUser();
        if (user == null || s.getTenantId() == null) return StaffMemberResponse.SystemAccess.NONE;
        return staffMembership(user, s.getTenantId())
                .map(m -> new StaffMemberResponse.SystemAccess(true, user.getEmail(), m.getRole().name(), m.getStatus().name(),
                        membershipRepository.findByUserId(user.getId()).stream().anyMatch(o -> !s.getTenantId().equals(o.getTenantId()))))
                .orElse(StaffMemberResponse.SystemAccess.NONE);
    }
}
