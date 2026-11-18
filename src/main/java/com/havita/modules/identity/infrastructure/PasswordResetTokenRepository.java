package com.havita.modules.identity.infrastructure;

import com.havita.modules.identity.domain.PasswordResetToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, UUID> {
    @EntityGraph(attributePaths = {"user"})
    Optional<PasswordResetToken> findByToken(String token);
}
