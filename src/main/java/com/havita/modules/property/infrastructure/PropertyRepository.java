package com.havita.modules.property.infrastructure;

import com.havita.modules.property.domain.PropertyStatus;

import com.havita.modules.property.domain.Property;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PropertyRepository extends JpaRepository<Property, UUID> {

    List<Property> findByTenantId(UUID tenantId);

    Page<Property> findByTenantId(UUID tenantId, Pageable pageable);

    Optional<Property> findByIdAndTenantId(UUID id, UUID tenantId);

    /** SEC-8: transferencias de titularidad; bloquea la unidad para que dos transferencias simultaneas se serialicen. */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Property p where p.id = :id and p.tenantId = :tenantId")
    Optional<Property> findByIdAndTenantIdForUpdate(@org.springframework.data.repository.query.Param("id") UUID id,
                                                    @org.springframework.data.repository.query.Param("tenantId") UUID tenantId);

    Optional<Property> findByTenantIdAndNumber(UUID tenantId, String number);

    boolean existsByTenantIdAndNumber(UUID tenantId, String number);

    boolean existsByTenantIdAndNumberIgnoreCase(UUID tenantId, String number);

    List<Property> findByTenantIdAndStructureId(UUID tenantId, UUID structureId);

    void deleteByTenantIdAndStructureId(UUID tenantId, UUID structureId);

    Page<Property> findByTenantIdAndStatus(UUID tenantId, PropertyStatus status, Pageable pageable);

    long countByTenantId(UUID tenantId);

    long countByTenantIdAndStatus(UUID tenantId, PropertyStatus status);

    @Query("SELECT p FROM Property p WHERE p.tenantId = :tenantId AND (" +
           "LOWER(p.number) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
           "LOWER(p.name) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
           "LOWER(p.path) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
           "EXISTS (SELECT r FROM Resident r WHERE r.property = p AND r.status = com.havita.modules.resident.domain.ResidentStatus.ACTIVE AND LOWER(r.name) LIKE LOWER(CONCAT('%', :search, '%'))))")
    Page<Property> searchProperties(UUID tenantId, String search, Pageable pageable);

    @Query("SELECT COALESCE(SUM(p.coefficient), 0) FROM Property p WHERE p.tenantId = :tenantId")
    BigDecimal sumCoefficientByTenantId(UUID tenantId);

    /**
     * Cierre E3: fila estable que serializa los derechos de voto de una unidad (registrar asistencia, delegar y revocar
     * el voto). FOR NO KEY UPDATE no choca con los FOR KEY SHARE que toman las inserciones que referencian la unidad
     * (votos, asistencias, delegaciones), asi que no bloquea esas escrituras ni provoca interbloqueos con el voto.
     * Vacio si la unidad no existe o es de otra comunidad.
     */
    @Query(value = "SELECT CAST(id AS varchar) FROM properties WHERE id = :id AND tenant_id = :tenantId FOR NO KEY UPDATE",
           nativeQuery = true)
    List<String> lockForVotingRights(@org.springframework.data.repository.query.Param("id") java.util.UUID id,
                                     @org.springframework.data.repository.query.Param("tenantId") java.util.UUID tenantId);
}
