package com.havita.support;

import com.havita.modules.identity.domain.Membership;
import com.havita.modules.identity.domain.MembershipStatus;
import com.havita.modules.identity.domain.RoleType;
import com.havita.modules.identity.domain.User;
import com.havita.modules.identity.infrastructure.MembershipRepository;
import com.havita.modules.identity.infrastructure.UserRepository;
import com.havita.modules.organization.domain.Tenant;
import com.havita.modules.organization.infrastructure.TenantRepository;
import com.havita.modules.property.domain.Property;
import com.havita.modules.property.domain.PropertyStatus;
import com.havita.modules.property.infrastructure.PropertyRepository;
import com.havita.modules.resident.domain.Resident;
import com.havita.modules.resident.domain.ResidentStatus;
import com.havita.modules.resident.infrastructure.ResidentRepository;
import com.havita.security.JwtService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Datos sinteticos de dos comunidades independientes (A y B) para pruebas de aislamiento.
 * Cada llamada a {@link #create()} usa identificadores unicos, por lo que las pruebas no comparten estado.
 */
@Component
public class TwoTenantFixture {

    @Autowired private TenantRepository tenantRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private MembershipRepository membershipRepository;
    @Autowired private PropertyRepository propertyRepository;
    @Autowired private ResidentRepository residentRepository;
    @Autowired private JwtService jwtService;

    public Community create() {
        return new Community(community("a"), community("b"));
    }

    private Side community(String label) {
        String suffix = label + UUID.randomUUID().toString().substring(0, 8);
        Tenant tenant = Tenant.builder()
                .name("Comunidad " + suffix).slug("c-" + suffix).subdomain("c" + suffix)
                .build();
        tenant = tenantRepository.save(tenant);
        UUID tenantId = tenant.getId();

        User admin = user("admin-" + suffix);
        User owner = user("owner-" + suffix);
        User neighbour = user("vecino-" + suffix);
        User guard = user("porteria-" + suffix);
        membership(admin, tenantId, RoleType.COMMUNITY_ADMIN);
        membership(owner, tenantId, RoleType.RESIDENT);
        membership(neighbour, tenantId, RoleType.RESIDENT);
        membership(guard, tenantId, RoleType.SECURITY);

        Property home = property(tenantId, "T1-101-" + suffix, PropertyStatus.OCCUPIED);
        Property neighbourHome = property(tenantId, "T1-102-" + suffix, PropertyStatus.OCCUPIED);
        Property vacant = property(tenantId, "T1-103-" + suffix, PropertyStatus.VACANT);

        Resident ownerResident = resident(tenantId, home, owner, "Propietario " + suffix, "Propietario");
        Resident neighbourResident = resident(tenantId, neighbourHome, neighbour, "Vecino " + suffix, "Propietario");
        Resident censusOnly = resident(tenantId, home, null, "Familiar " + suffix, "Residente");

        return new Side(tenantId, admin, owner, neighbour, guard, home, neighbourHome, vacant,
                ownerResident, neighbourResident, censusOnly);
    }

    private User user(String handle) {
        User u = User.builder()
                .email(handle + "@example.test").passwordHash("{noop}not-used").fullName("Cuenta " + handle)
                .phone("3000000000")
                .build();
        return userRepository.save(u);
    }

    private void membership(User user, UUID tenantId, RoleType role) {
        membershipRepository.save(Membership.builder()
                .user(user).tenantId(tenantId).role(role).status(MembershipStatus.ACTIVE).build());
    }

    private Property property(UUID tenantId, String number, PropertyStatus status) {
        Property p = Property.builder().number(number).name("Apto " + number).status(status).build();
        p.setTenantId(tenantId);
        return propertyRepository.save(p);
    }

    private Resident resident(UUID tenantId, Property property, User user, String name, String type) {
        Resident r = Resident.builder()
                .property(property).user(user).name(name).type(type)
                .email(name.toLowerCase().replace(' ', '.') + "@example.test")
                .phone("3100000000").documentId("DOC-" + UUID.randomUUID().toString().substring(0, 6))
                .status(ResidentStatus.ACTIVE)
                .build();
        r.setTenantId(tenantId);
        return residentRepository.save(r);
    }

    /** Bearer token real firmado por la aplicacion, con las membresias guardadas del usuario. */
    public String bearer(User user) {
        List<Membership> memberships = membershipRepository.findByUserId(user.getId());
        return "Bearer " + jwtService.generateAccessToken(user.getId(), user.getEmail(), memberships, null);
    }

    public record Community(Side a, Side b) {}

    public record Side(UUID tenantId, User admin, User owner, User neighbour, User guard,
                       Property home, Property neighbourHome, Property vacant,
                       Resident ownerResident, Resident neighbourResident, Resident censusOnly) {}
}
