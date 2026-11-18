package com.havita.modules.assembly.infrastructure;

import com.havita.modules.assembly.domain.Poll;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PollRepository extends JpaRepository<Poll, UUID> {

    /**
     * Fase 2 / E3 (D8): la pregunta se lee con FOR SHARE al votar. Votos de unidades distintas siguen en paralelo, y un
     * cierre concurrente (UPDATE) espera a que terminen; un voto que llega despues del cierre ve el estado CLOSED.
     */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_READ)
    @org.springframework.data.jpa.repository.Query("SELECT p FROM Poll p WHERE p.id = :id AND p.tenantId = :tenantId")
    Optional<Poll> lockForVote(@org.springframework.data.repository.query.Param("id") UUID id,
                               @org.springframework.data.repository.query.Param("tenantId") UUID tenantId);

    /**
     * Cierre E5: abrir o cerrar bloquea la pregunta (FOR NO KEY UPDATE) ANTES de leerla. Asi el cierre espera a los votos
     * en curso (FOR SHARE) y fija closed_at despues de ellos: antes, closed_at se calculaba antes de esperar y un voto
     * aceptado justo antes del cierre quedaba con hora posterior al cierre.
     */
    @org.springframework.data.jpa.repository.Query(value = "SELECT CAST(id AS varchar) FROM polls WHERE id = :id "
            + "AND tenant_id = :tenantId FOR NO KEY UPDATE", nativeQuery = true)
    List<String> lockRow(@org.springframework.data.repository.query.Param("id") UUID id,
                         @org.springframework.data.repository.query.Param("tenantId") UUID tenantId);

    List<Poll> findByTenantId(UUID tenantId);

    List<Poll> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);

    Page<Poll> findByTenantId(UUID tenantId, Pageable pageable);

    Optional<Poll> findByIdAndTenantId(UUID id, UUID tenantId);

    List<Poll> findByAssemblyId(UUID assemblyId);

    long countByAssemblyId(UUID assemblyId);
}
