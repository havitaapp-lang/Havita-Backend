package com.havita.modules.assembly.infrastructure;

import com.havita.modules.assembly.domain.AssemblyAttendee;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AssemblyAttendeeRepository extends JpaRepository<AssemblyAttendee, UUID> {

        List<AssemblyAttendee> findByAssemblyId(UUID assemblyId);

    List<AssemblyAttendee> findByPropertyId(UUID propertyId);

    Optional<AssemblyAttendee> findByAssemblyIdAndPropertyId(UUID assemblyId, UUID propertyId);

    boolean existsByAssemblyIdAndPropertyId(UUID assemblyId, UUID propertyId);

    /**
     * Fase 2 / E3 (D8): punto de serializacion del voto de una unidad. La fila de asistencia (unica por asamblea y unidad,
     * obligatoria para votar) se bloquea FOR UPDATE ANTES de comprobar si la unidad ya voto: dos votos concurrentes de
     * la misma unidad (o del propietario y su delegado) se ejecutan uno detras de otro.
     */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("SELECT a FROM AssemblyAttendee a WHERE a.assembly.id = :assemblyId "
            + "AND a.property.id = :propertyId AND a.tenantId = :tenantId")
    Optional<AssemblyAttendee> lockForVote(@org.springframework.data.repository.query.Param("assemblyId") UUID assemblyId,
                                           @org.springframework.data.repository.query.Param("propertyId") UUID propertyId,
                                           @org.springframework.data.repository.query.Param("tenantId") UUID tenantId);

    long countByAssemblyId(UUID assemblyId);

    @Query("SELECT COALESCE(SUM(a.coefficient), 0) FROM AssemblyAttendee a WHERE a.assembly.id = :assemblyId")
    BigDecimal sumAttendedCoefficient(UUID assemblyId);

    /**
     * Fase 2 / E5 (D10): ¿la unidad tiene asistencia registrada en alguna asamblea ABIERTA (programada o en curso) de la
     * comunidad? La asistencia no tiene estado propio (no hay cancelada ni retirada): una fila de una asamblea finalizada
     * es historica y no cuenta.
     */
    @Query("SELECT COUNT(a) > 0 FROM AssemblyAttendee a WHERE a.property.id = :propertyId AND a.tenantId = :tenantId "
            + "AND a.assembly.status <> com.havita.modules.assembly.domain.AssemblyStatus.ENDED")
    boolean existsOpenByProperty(@org.springframework.data.repository.query.Param("propertyId") UUID propertyId,
                                 @org.springframework.data.repository.query.Param("tenantId") UUID tenantId);

    /**
     * Fase 2 / E5: filas de asistencia de la unidad en asambleas ABIERTAS (programadas o en curso), bloqueadas en orden de
     * id. Delegar o revocar las bloquea despues de la unidad, en el mismo orden que el voto (asistencia), para que un
     * cambio de delegacion y un voto no se crucen; una votacion puede estar abierta en una asamblea programada.
     * Cierre E5: "FOR NO KEY UPDATE OF a" bloquea SOLO las filas de asistencia. La version JPQL con @Lock y el join a la
     * asamblea emitia "FOR NO KEY UPDATE" sin OF y bloqueaba tambien las asambleas, en un orden que dependia del id
     * (aleatorio) de cada asistencia: dos unidades con asistencia en las mismas dos asambleas podian interbloquearse y
     * unidades independientes se serializaban sin necesidad.
     */
    @org.springframework.data.jpa.repository.Query(value = "SELECT CAST(a.id AS varchar) FROM assembly_attendees a "
            + "JOIN assemblies s ON s.id = a.assembly_id WHERE a.property_id = :propertyId AND a.tenant_id = :tenantId "
            + "AND s.status <> 'ENDED' ORDER BY a.id FOR NO KEY UPDATE OF a", nativeQuery = true)
    List<String> lockOpenByProperty(@org.springframework.data.repository.query.Param("propertyId") UUID propertyId,
                                              @org.springframework.data.repository.query.Param("tenantId") UUID tenantId);

    /**
     * Fase 2 / E5 (D9) y cierre E5 (D12): ¿el usuario figura como asistente de la unidad en una asamblea ABIERTA
     * (programada, incluido el pre-registro administrativo, o en curso)? La asistencia lo identifica por su relacion de
     * residente; las asambleas finalizadas no cuentan.
     */
    @Query("SELECT COUNT(a) > 0 FROM AssemblyAttendee a WHERE a.property.id = :propertyId AND a.tenantId = :tenantId "
            + "AND a.assembly.status <> com.havita.modules.assembly.domain.AssemblyStatus.ENDED "
            + "AND a.resident.user.id = :userId")
    boolean existsOpenByPropertyAndUser(@org.springframework.data.repository.query.Param("propertyId") UUID propertyId,
                                              @org.springframework.data.repository.query.Param("tenantId") UUID tenantId,
                                              @org.springframework.data.repository.query.Param("userId") UUID userId);

    /** Fase 2 / E5: asistencias de una asamblea para un conjunto de unidades (una consulta, sin N+1). */
    @Query("SELECT a FROM AssemblyAttendee a LEFT JOIN FETCH a.resident r LEFT JOIN FETCH r.user "
            + "WHERE a.assembly.id = :assemblyId AND a.tenantId = :tenantId AND a.property.id IN :propertyIds")
    List<AssemblyAttendee> findByAssemblyAndProperties(@org.springframework.data.repository.query.Param("assemblyId") UUID assemblyId,
                                                       @org.springframework.data.repository.query.Param("tenantId") UUID tenantId,
                                                       @org.springframework.data.repository.query.Param("propertyIds") java.util.Collection<UUID> propertyIds);
}
