package com.havita.modules.identity.infrastructure;

import com.havita.modules.identity.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

import com.havita.modules.identity.domain.RoleType;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByEmailIgnoreCase(String email);
    boolean existsByEmailIgnoreCase(String email);

    @Query("SELECT DISTINCT m.user FROM Membership m WHERE m.role IN :roles")
    List<User> findByMembershipRolesIn(@Param("roles") List<RoleType> roles);
}
