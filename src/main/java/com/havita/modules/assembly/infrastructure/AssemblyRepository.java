package com.havita.modules.assembly.infrastructure;

import com.havita.modules.assembly.domain.Assembly;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AssemblyRepository extends JpaRepository<Assembly, UUID> {

    List<Assembly> findByTenantId(UUID tenantId);

    Page<Assembly> findByTenantId(UUID tenantId, Pageable pageable);

    /** Cierre E5: asambleas de la comunidad en un estado (p. ej. la que esta EN CURSO), sin depender de la paginacion. */
    Page<Assembly> findByTenantIdAndStatus(UUID tenantId, com.havita.modules.assembly.domain.AssemblyStatus status, Pageable pageable);

    Optional<Assembly> findByIdAndTenantId(UUID id, UUID tenantId);

    List<Assembly> findByTenantIdAndAssemblyDate(UUID tenantId, LocalDate date);

    long countByTenantId(UUID tenantId);

    /**
     * Fase 2 / E5: bloquea la fila de la asamblea (FOR NO KEY UPDATE) antes de recalcular su quorum o cambiar su estado.
     * No choca con los FOR KEY SHARE de las inserciones que la referencian (asistencias, preguntas), asi que no crea
     * ciclos con ellas; si serializa los recalculos de quorum entre si y con iniciar o finalizar.
     */
    @org.springframework.data.jpa.repository.Query(value = "SELECT CAST(id AS varchar) FROM assemblies WHERE id = :id "
            + "AND tenant_id = :tenantId FOR NO KEY UPDATE", nativeQuery = true)
    List<String> lockRow(@org.springframework.data.repository.query.Param("id") UUID id,
                         @org.springframework.data.repository.query.Param("tenantId") UUID tenantId);

    /**
     * Cierre E5 / D14: el voto lee el estado de la asamblea con FOR SHARE (ultimo bloqueo del orden global). Choca con el
     * FOR NO KEY UPDATE de finalizar: un voto y la finalizacion no se cruzan, y un voto posterior ve ENDED. Los votos
     * entre si no se bloquean (FOR SHARE es compatible).
     */
    @org.springframework.data.jpa.repository.Query(value = "SELECT status FROM assemblies WHERE id = :id "
            + "AND tenant_id = :tenantId FOR SHARE", nativeQuery = true)
    List<String> lockStatusForVote(@org.springframework.data.repository.query.Param("id") UUID id,
                                   @org.springframework.data.repository.query.Param("tenantId") UUID tenantId);
}
