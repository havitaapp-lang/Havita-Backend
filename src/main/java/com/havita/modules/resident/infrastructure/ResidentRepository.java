package com.havita.modules.resident.infrastructure;

import com.havita.modules.resident.domain.ResidentStatus;

import com.havita.modules.resident.domain.Resident;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.repository.query.Param;

@Repository
public interface ResidentRepository extends JpaRepository<Resident, UUID> {

    @EntityGraph(attributePaths = {"property", "user"})
    List<Resident> findByTenantId(UUID tenantId);

    @EntityGraph(attributePaths = {"property", "user"})
    List<Resident> findByUserIdAndTenantId(UUID userId, UUID tenantId);

    @EntityGraph(attributePaths = {"property", "user"})
    Optional<Resident> findFirstByUserIdAndTenantIdAndStatus(UUID userId, UUID tenantId, ResidentStatus status);

    @EntityGraph(attributePaths = {"property", "user"})
    Page<Resident> findByTenantId(UUID tenantId, Pageable pageable);

    @EntityGraph(attributePaths = {"property", "user"})
    Optional<Resident> findByIdAndTenantId(UUID id, UUID tenantId);

    @EntityGraph(attributePaths = {"property", "user"})
    List<Resident> findByTenantIdAndPropertyId(UUID tenantId, UUID propertyId);

    /** Fase 2 / E5: relaciones con un estado dado de varias unidades de la comunidad (una consulta, sin N+1). */
    List<Resident> findByTenantIdAndPropertyIdInAndStatus(UUID tenantId, java.util.Collection<UUID> propertyIds, ResidentStatus status);

    @EntityGraph(attributePaths = {"property", "user"})
    Page<Resident> findByTenantIdAndStatus(UUID tenantId, ResidentStatus status, Pageable pageable);

    long countByTenantId(UUID tenantId);

    long countByTenantIdAndStatus(UUID tenantId, ResidentStatus status);

    boolean existsByTenantIdAndPropertyStructureId(UUID tenantId, UUID structureId);

    /**
     * Directorio de residentes (TASK-008): filtros combinables, siempre dentro del tenant, paginados en PostgreSQL.
     * {@code status} nulo = todos los estados; {@code type} y {@code search} vacios = sin filtro. La busqueda cubre
     * nombre, documento, correo, telefono y la unidad (numero, nombre y torre). Propiedad, torre y usuario se cargan
     * en la misma consulta (sin N+1); el COUNT es una consulta aparte sin joins de carga.
     */
    @EntityGraph(attributePaths = {"property", "property.structure", "user"})
    @Query(value = "SELECT r FROM Resident r JOIN r.property p LEFT JOIN p.structure s " +
           "WHERE r.tenantId = :tenantId " +
           "AND (:structureMode = '' OR (:structureMode = 'ID' AND s.id = :structureId) OR (:structureMode = 'NONE' AND s.id IS NULL)) " +
           "AND (:status IS NULL OR r.status = :status) " +
           "AND (:type = '' OR LOWER(r.type) = :type) " +
           "AND (:search = '' OR LOWER(r.name) LIKE :search OR LOWER(r.documentId) LIKE :search " +
           "  OR LOWER(r.email) LIKE :search OR LOWER(r.phone) LIKE :search " +
           "  OR LOWER(p.number) LIKE :search OR LOWER(p.name) LIKE :search OR LOWER(s.name) LIKE :search)",
           countQuery = "SELECT COUNT(r) FROM Resident r JOIN r.property p LEFT JOIN p.structure s " +
           "WHERE r.tenantId = :tenantId " +
           "AND (:structureMode = '' OR (:structureMode = 'ID' AND s.id = :structureId) OR (:structureMode = 'NONE' AND s.id IS NULL)) " +
           "AND (:status IS NULL OR r.status = :status) " +
           "AND (:type = '' OR LOWER(r.type) = :type) " +
           "AND (:search = '' OR LOWER(r.name) LIKE :search OR LOWER(r.documentId) LIKE :search " +
           "  OR LOWER(r.email) LIKE :search OR LOWER(r.phone) LIKE :search " +
           "  OR LOWER(p.number) LIKE :search OR LOWER(p.name) LIKE :search OR LOWER(s.name) LIKE :search)")
    Page<Resident> searchDirectory(@Param("tenantId") UUID tenantId,
                                   @Param("structureMode") String structureMode,
                                   @Param("structureId") UUID structureId,
                                   @Param("status") ResidentStatus status,
                                   @Param("type") String type,
                                   @Param("search") String search,
                                   Pageable pageable);

    List<Resident> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);

    boolean existsByPropertyIdAndTypeIgnoreCaseAndStatus(UUID propertyId, String type, ResidentStatus status);

    @EntityGraph(attributePaths = {"property", "user", "parentResident", "grantedBy"})
    List<Resident> findByPropertyIdAndStatus(UUID propertyId, ResidentStatus status);

    @EntityGraph(attributePaths = {"property", "user", "parentResident", "grantedBy"})
    List<Resident> findByParentResidentIdAndStatus(UUID parentResidentId, ResidentStatus status);

    @EntityGraph(attributePaths = {"property", "user", "parentResident", "grantedBy"})
    Optional<Resident> findByPropertyIdAndTypeIgnoreCaseAndStatus(UUID propertyId, String type, ResidentStatus status);

    @EntityGraph(attributePaths = {"property", "user"})
    @Query("SELECT r FROM Resident r WHERE " +
           "((:userId IS NOT NULL AND r.user IS NOT NULL AND r.user.id = :userId) OR " +
           "(:email IS NOT NULL AND r.email IS NOT NULL AND LOWER(r.email) = LOWER(:email))) " +
           "AND r.status = :status AND LOWER(r.type) IN ('residente', 'arrendatario')")
    List<Resident> findActiveNonOwnerResidences(UUID userId, String email, ResidentStatus status);

    @EntityGraph(attributePaths = {"property", "user"})
    @Query("SELECT r FROM Resident r WHERE r.tenantId = :tenantId AND " +
           "((:userId IS NOT NULL AND r.user IS NOT NULL AND r.user.id = :userId) OR " +
           "(:email IS NOT NULL AND r.email IS NOT NULL AND LOWER(r.email) = LOWER(:email)))")
    List<Resident> findByUserIdOrEmailAndTenantId(@Param("userId") UUID userId, @Param("email") String email, @Param("tenantId") UUID tenantId);

    /**
     * Fase 2 / E1 (ResidentUnitResolver): relaciones del usuario en la comunidad con el estado pedido, con unidad y
     * estructura en la misma consulta (sin N+1). Vinculacion por cuenta; el correo solo cuenta para registros del
     * censo SIN cuenta vinculada (compatibilidad, D2): un registro vinculado a otra cuenta nunca da acceso.
     */
    @Query("SELECT r FROM Resident r JOIN FETCH r.property p LEFT JOIN FETCH p.structure LEFT JOIN r.user u " +
           "WHERE r.tenantId = :tenantId AND r.status = :status AND " +
           "(u.id = :userId OR (u IS NULL AND :email IS NOT NULL AND r.email IS NOT NULL AND LOWER(r.email) = LOWER(:email)))")
    List<Resident> findUnitRelations(@Param("userId") UUID userId, @Param("email") String email,
                                     @Param("tenantId") UUID tenantId, @Param("status") ResidentStatus status);

    /** Igual criterio que {@link #findUnitRelations}, solo existencia (lo usa el filtro en cada peticion de RESIDENT). */
    @Query("SELECT COUNT(r) > 0 FROM Resident r LEFT JOIN r.user u " +
           "WHERE r.tenantId = :tenantId AND r.status = :status AND r.property IS NOT NULL AND " +
           "(u.id = :userId OR (u IS NULL AND :email IS NOT NULL AND r.email IS NOT NULL AND LOWER(r.email) = LOWER(:email)))")
    boolean existsUnitRelation(@Param("userId") UUID userId, @Param("email") String email,
                               @Param("tenantId") UUID tenantId, @Param("status") ResidentStatus status);

    @EntityGraph(attributePaths = {"property", "user"})
    @Query("SELECT r FROM Resident r WHERE " +
           "((:userId IS NOT NULL AND r.user IS NOT NULL AND r.user.id = :userId) OR " +
           "(:email IS NOT NULL AND r.email IS NOT NULL AND LOWER(r.email) = LOWER(:email)))")
    List<Resident> findAllByUserIdOrEmail(@Param("userId") UUID userId, @Param("email") String email);
}
