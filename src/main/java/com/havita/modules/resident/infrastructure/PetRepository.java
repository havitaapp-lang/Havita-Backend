package com.havita.modules.resident.infrastructure;

import com.havita.modules.resident.domain.Pet;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PetRepository extends JpaRepository<Pet, UUID> {

    List<Pet> findByTenantId(UUID tenantId);

    Page<Pet> findByTenantId(UUID tenantId, Pageable pageable);

    Optional<Pet> findByIdAndTenantId(UUID id, UUID tenantId);

    /**
     * IDOR prevention: verifica que la mascota pertenece al tenant Y a la propiedad del usuario.
     * Usado en ResidentSelfController para que un residente solo elimine sus propias mascotas.
     */
    Optional<Pet> findByIdAndTenantIdAndPropertyId(UUID id, UUID tenantId, UUID propertyId);

    List<Pet> findByTenantIdAndPropertyId(UUID tenantId, UUID propertyId);

    long countByTenantId(UUID tenantId);
}
