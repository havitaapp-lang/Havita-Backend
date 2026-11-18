package com.havita.modules.resident.infrastructure;

import com.havita.modules.resident.domain.Vehicle;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface VehicleRepository extends JpaRepository<Vehicle, UUID> {

    List<Vehicle> findByTenantId(UUID tenantId);

    Page<Vehicle> findByTenantId(UUID tenantId, Pageable pageable);

    Optional<Vehicle> findByIdAndTenantId(UUID id, UUID tenantId);

    /**
     * IDOR prevention: verifica que el vehículo pertenece al tenant Y a la propiedad del usuario.
     * Usado en ResidentSelfController para que un residente solo elimine sus propios vehículos.
     */
    Optional<Vehicle> findByIdAndTenantIdAndPropertyId(UUID id, UUID tenantId, UUID propertyId);

    Optional<Vehicle> findByTenantIdAndPlateIgnoreCase(UUID tenantId, String plate);

    boolean existsByTenantIdAndPlateIgnoreCase(UUID tenantId, String plate);

    List<Vehicle> findByTenantIdAndPropertyId(UUID tenantId, UUID propertyId);

    long countByTenantId(UUID tenantId);
}
