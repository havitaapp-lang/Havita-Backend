package com.havita.modules.resident.application;

import com.havita.common.exception.BusinessRuleConflictException;
import com.havita.common.exception.ResourceNotFoundException;
import com.havita.modules.property.domain.Property;
import com.havita.modules.property.infrastructure.PropertyRepository;
import com.havita.modules.resident.api.dto.CreatePetRequest;
import com.havita.modules.resident.api.dto.CreateVehicleRequest;
import com.havita.modules.resident.api.dto.PetResponse;
import com.havita.modules.resident.api.dto.VehicleResponse;
import com.havita.modules.resident.domain.Pet;
import com.havita.modules.resident.domain.Resident;
import com.havita.modules.resident.domain.Vehicle;
import com.havita.modules.resident.infrastructure.PetRepository;
import com.havita.modules.resident.infrastructure.ResidentRepository;
import com.havita.modules.resident.infrastructure.VehicleRepository;
import com.havita.security.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class VehicleAndPetService {

    private final VehicleRepository vehicleRepository;
    private final PetRepository petRepository;
    private final PropertyRepository propertyRepository;
    private final ResidentRepository residentRepository;

    // --- Vehículos ---

    @Transactional(readOnly = true)
    public Page<VehicleResponse> findAllVehicles(Pageable pageable) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        return vehicleRepository.findByTenantId(tenantId, pageable).map(this::toVehicleResponse);
    }

    @Transactional(readOnly = true)
    public List<VehicleResponse> findVehiclesByProperty(UUID propertyId) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        return vehicleRepository.findByTenantIdAndPropertyId(tenantId, propertyId)
                .stream()
                .map(this::toVehicleResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<VehicleResponse> findMyVehiclesByProperty(UUID propertyId, UUID userId) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        // Encuentra las asociaciones del usuario en esta propiedad
        List<com.havita.modules.resident.domain.Resident> myResidents = residentRepository.findByUserIdAndTenantId(userId, tenantId).stream()
                .filter(r -> r.getProperty().getId().equals(propertyId))
                .toList();
        if (myResidents.isEmpty()) return List.of();

        return vehicleRepository.findByTenantIdAndPropertyId(tenantId, propertyId)
                .stream()
                // Solo devuelve vehículos que están asociados directamente a un registro de Residente que pertenece al usuario
                .filter(v -> v.getResident() != null && myResidents.stream().anyMatch(r -> r.getId().equals(v.getResident().getId())))
                .map(this::toVehicleResponse)
                .toList();
    }

    @Transactional
    public VehicleResponse createVehicle(CreateVehicleRequest request) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();

        if (vehicleRepository.existsByTenantIdAndPlateIgnoreCase(tenantId, request.plate().trim())) {
            throw new BusinessRuleConflictException(
                    "VEHICLE_PLATE_EXISTS",
                    "Ya existe un vehiculo con la placa '" + request.plate() + "' registrado en esta copropiedad.");
        }

        Property property = propertyRepository.findByIdAndTenantId(request.propertyId(), tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Property", request.propertyId()));

        Resident resident = null;
        if (request.residentId() != null) {
            resident = residentRepository.findByIdAndTenantId(request.residentId(), tenantId)
                    .orElseThrow(() -> new ResourceNotFoundException("Resident", request.residentId()));
        }

        Vehicle vehicle = Vehicle.builder()
                .property(property)
                .resident(resident)
                .plate(request.plate().trim().toUpperCase())
                .type(request.type() != null ? request.type() : "Automóvil")
                .brand(request.brand())
                .model(request.model())
                .color(request.color())
                .parkingSpot(request.parkingSpot())
                .build();
        vehicle.setTenantId(tenantId);

        vehicle = vehicleRepository.save(vehicle);

        // Incrementar conteo de vehículos en la propiedad
        property.setVehiclesCount((property.getVehiclesCount() != null ? property.getVehiclesCount() : 0) + 1);
        propertyRepository.save(property);

        log.info("Vehiculo registrado: placa {} en propiedad {}", vehicle.getPlate(), property.getNumber());
        return toVehicleResponse(vehicle);
    }

    // --- Mascotas ---

    @Transactional(readOnly = true)
    public Page<PetResponse> findAllPets(Pageable pageable) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        return petRepository.findByTenantId(tenantId, pageable).map(this::toPetResponse);
    }

    @Transactional(readOnly = true)
    public List<PetResponse> findPetsByProperty(UUID propertyId) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        return petRepository.findByTenantIdAndPropertyId(tenantId, propertyId)
                .stream()
                .map(this::toPetResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<PetResponse> findMyPetsByProperty(UUID propertyId, UUID userId) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        List<com.havita.modules.resident.domain.Resident> myResidents = residentRepository.findByUserIdAndTenantId(userId, tenantId).stream()
                .filter(r -> r.getProperty().getId().equals(propertyId))
                .toList();
        if (myResidents.isEmpty()) return List.of();

        return petRepository.findByTenantIdAndPropertyId(tenantId, propertyId)
                .stream()
                .filter(p -> p.getResident() != null && myResidents.stream().anyMatch(r -> r.getId().equals(p.getResident().getId())))
                .map(this::toPetResponse)
                .toList();
    }

    @Transactional
    public PetResponse createPet(CreatePetRequest request) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();

        Property property = propertyRepository.findByIdAndTenantId(request.propertyId(), tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Property", request.propertyId()));

        Resident resident = null;
        if (request.residentId() != null) {
            resident = residentRepository.findByIdAndTenantId(request.residentId(), tenantId)
                    .orElseThrow(() -> new ResourceNotFoundException("Resident", request.residentId()));
        }

        Pet pet = Pet.builder()
                .property(property)
                .resident(resident)
                .name(request.name().trim())
                .type(request.type() != null ? request.type() : "Perro")
                .breed(request.breed())
                .color(request.color())
                .isDangerousBreed(request.isDangerousBreed() != null ? request.isDangerousBreed() : false)
                .vaccinationCardUrl(request.vaccinationCardUrl())
                .photoUrl(request.photoUrl())
                .build();
        pet.setTenantId(tenantId);

        pet = petRepository.save(pet);

        // Incrementar conteo de mascotas en la propiedad
        property.setPetsCount((property.getPetsCount() != null ? property.getPetsCount() : 0) + 1);
        propertyRepository.save(property);

        log.info("Mascota registrada: {} en propiedad {}", pet.getName(), property.getNumber());
        return toPetResponse(pet);
    }

    private VehicleResponse toVehicleResponse(Vehicle vehicle) {
        return new VehicleResponse(
                vehicle.getId(),
                vehicle.getTenantId(),
                vehicle.getProperty().getId(),
                vehicle.getProperty().getNumber(),
                vehicle.getResident() != null ? vehicle.getResident().getId() : null,
                vehicle.getResident() != null ? vehicle.getResident().getName() : null,
                vehicle.getPlate(),
                vehicle.getType(),
                vehicle.getBrand(),
                vehicle.getModel(),
                vehicle.getColor(),
                vehicle.getParkingSpot(),
                vehicle.getCreatedAt(),
                vehicle.getUpdatedAt()
        );
    }

    private PetResponse toPetResponse(Pet pet) {
        return new PetResponse(
                pet.getId(),
                pet.getTenantId(),
                pet.getProperty().getId(),
                pet.getProperty().getNumber(),
                pet.getResident() != null ? pet.getResident().getId() : null,
                pet.getResident() != null ? pet.getResident().getName() : null,
                pet.getName(),
                pet.getType(),
                pet.getBreed(),
                pet.getColor(),
                pet.getIsDangerousBreed(),
                pet.getVaccinationCardUrl(),
                pet.getPhotoUrl(),
                pet.getCreatedAt(),
                pet.getUpdatedAt()
        );
    }

    // --- Eliminación con IDOR protection ---

    /**
     * Elimina un vehículo verificando que pertenece al tenant y a la propiedad indicada.
     * Si propertyId es null (admin), sólo valida el tenant.
     */
    @Transactional
    public void deleteVehicle(UUID vehicleId, UUID propertyId) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        Vehicle vehicle;
        if (propertyId != null) {
            vehicle = vehicleRepository.findByIdAndTenantIdAndPropertyId(vehicleId, tenantId, propertyId)
                    .orElseThrow(() -> new ResourceNotFoundException("Vehicle", vehicleId));
        } else {
            vehicle = vehicleRepository.findByIdAndTenantId(vehicleId, tenantId)
                    .orElseThrow(() -> new ResourceNotFoundException("Vehicle", vehicleId));
        }

        Property property = vehicle.getProperty();
        vehicleRepository.delete(vehicle);

        // Decrementar contador en la propiedad
        if (property.getVehiclesCount() != null && property.getVehiclesCount() > 0) {
            property.setVehiclesCount(property.getVehiclesCount() - 1);
            propertyRepository.save(property);
        }
        log.info("Vehiculo eliminado: {} (Tenant: {})", vehicleId, tenantId);
    }

    /**
     * Actualiza un vehículo verificando que pertenece al usuario logueado en esa propiedad.
     */
    @Transactional
    public VehicleResponse updateMyVehicle(UUID vehicleId, UUID propertyId, UUID userId, CreateVehicleRequest request) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();

        List<com.havita.modules.resident.domain.Resident> myResidents = residentRepository.findByUserIdAndTenantId(userId, tenantId).stream()
                .filter(r -> r.getProperty().getId().equals(propertyId))
                .toList();

        Vehicle vehicle = vehicleRepository.findByIdAndTenantIdAndPropertyId(vehicleId, tenantId, propertyId)
                .orElseThrow(() -> new ResourceNotFoundException("Vehicle", vehicleId));

        final UUID vehicleResidentId = vehicle.getResident() != null ? vehicle.getResident().getId() : null;
        boolean ownsVehicle = vehicleResidentId != null && myResidents.stream().anyMatch(r -> r.getId().equals(vehicleResidentId));
        if (!ownsVehicle) {
            throw new com.havita.common.exception.BusinessRuleConflictException("ACCESS_DENIED", "No tienes permiso para modificar este vehiculo.");
        }

        if (!vehicle.getPlate().equalsIgnoreCase(request.plate().trim())) {
            if (vehicleRepository.existsByTenantIdAndPlateIgnoreCase(tenantId, request.plate().trim())) {
                throw new com.havita.common.exception.BusinessRuleConflictException("DUPLICATE_VEHICLE", 
                        "Ya existe un vehiculo con la placa '" + request.plate() + "' registrado en esta copropiedad.");
            }
            vehicle.setPlate(request.plate().trim().toUpperCase());
        }

        vehicle.setType(request.type() != null ? request.type() : "Automóvil");
        vehicle.setBrand(request.brand());
        vehicle.setModel(request.model());
        vehicle.setColor(request.color());
        vehicle.setParkingSpot(request.parkingSpot());

        vehicle = vehicleRepository.save(vehicle);
        log.info("Vehiculo actualizado por usuario: {} (Tenant: {})", vehicleId, tenantId);
        return toVehicleResponse(vehicle);
    }

    /**
     * Elimina un vehículo verificando que pertenece al usuario logueado en esa propiedad.
     */
    @Transactional
    public void deleteMyVehicle(UUID vehicleId, UUID propertyId, UUID userId) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        
        List<com.havita.modules.resident.domain.Resident> myResidents = residentRepository.findByUserIdAndTenantId(userId, tenantId).stream()
                .filter(r -> r.getProperty().getId().equals(propertyId))
                .toList();

        Vehicle vehicle = vehicleRepository.findByIdAndTenantIdAndPropertyId(vehicleId, tenantId, propertyId)
                .orElseThrow(() -> new ResourceNotFoundException("Vehicle", vehicleId));

        // Verificar que el vehículo esté asignado a uno de los Residentes de este Usuario
        boolean ownsVehicle = vehicle.getResident() != null && myResidents.stream().anyMatch(r -> r.getId().equals(vehicle.getResident().getId()));
        if (!ownsVehicle) {
            throw new com.havita.common.exception.BusinessRuleConflictException("ACCESS_DENIED", "No tienes permiso para eliminar este vehiculo.");
        }

        Property property = vehicle.getProperty();
        vehicleRepository.delete(vehicle);

        if (property.getVehiclesCount() != null && property.getVehiclesCount() > 0) {
            property.setVehiclesCount(property.getVehiclesCount() - 1);
            propertyRepository.save(property);
        }
        log.info("Vehiculo eliminado por usuario: {} (Tenant: {})", vehicleId, tenantId);
    }

    /**
     * Elimina una mascota verificando que pertenece al tenant y a la propiedad indicada.
     * Si propertyId es null (admin), slo valida el tenant.
     */
    @Transactional
    public void deletePet(UUID petId, UUID propertyId) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        Pet pet;
        if (propertyId != null) {
            pet = petRepository.findByIdAndTenantIdAndPropertyId(petId, tenantId, propertyId)
                    .orElseThrow(() -> new ResourceNotFoundException("Pet", petId));
        } else {
            pet = petRepository.findByIdAndTenantId(petId, tenantId)
                    .orElseThrow(() -> new ResourceNotFoundException("Pet", petId));
        }

        Property property = pet.getProperty();
        petRepository.delete(pet);

        // Decrementar contador en la propiedad
        if (property.getPetsCount() != null && property.getPetsCount() > 0) {
            property.setPetsCount(property.getPetsCount() - 1);
            propertyRepository.save(property);
        }
        log.info("Mascota eliminada: {} (Tenant: {})", petId, tenantId);
    }

    /**
     * Actualiza una mascota verificando que pertenece al usuario logueado en esa propiedad.
     */
    @Transactional
    public PetResponse updateMyPet(UUID petId, UUID propertyId, UUID userId, CreatePetRequest request) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();

        List<com.havita.modules.resident.domain.Resident> myResidents = residentRepository.findByUserIdAndTenantId(userId, tenantId).stream()
                .filter(r -> r.getProperty().getId().equals(propertyId))
                .toList();

        Pet pet = petRepository.findByIdAndTenantIdAndPropertyId(petId, tenantId, propertyId)
                .orElseThrow(() -> new ResourceNotFoundException("Pet", petId));

        final UUID petResidentId = pet.getResident() != null ? pet.getResident().getId() : null;
        boolean ownsPet = petResidentId != null && myResidents.stream().anyMatch(r -> r.getId().equals(petResidentId));
        if (!ownsPet) {
            throw new com.havita.common.exception.BusinessRuleConflictException("ACCESS_DENIED", "No tienes permiso para modificar esta mascota.");
        }

        pet.setName(request.name().trim());
        pet.setType(request.type() != null ? request.type() : "Perro");
        pet.setBreed(request.breed());
        pet.setColor(request.color());
        pet.setIsDangerousBreed(request.isDangerousBreed() != null ? request.isDangerousBreed() : false);
        pet.setVaccinationCardUrl(request.vaccinationCardUrl());
        pet.setPhotoUrl(request.photoUrl());

        pet = petRepository.save(pet);
        log.info("Mascota actualizada por usuario: {} (Tenant: {})", petId, tenantId);
        return toPetResponse(pet);
    }

    /**
     * Elimina una mascota verificando que pertenece al usuario logueado en esa propiedad.
     */
    @Transactional
    public void deleteMyPet(UUID petId, UUID propertyId, UUID userId) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        
        List<com.havita.modules.resident.domain.Resident> myResidents = residentRepository.findByUserIdAndTenantId(userId, tenantId).stream()
                .filter(r -> r.getProperty().getId().equals(propertyId))
                .toList();

        Pet pet = petRepository.findByIdAndTenantIdAndPropertyId(petId, tenantId, propertyId)
                .orElseThrow(() -> new ResourceNotFoundException("Pet", petId));

        boolean ownsPet = pet.getResident() != null && myResidents.stream().anyMatch(r -> r.getId().equals(pet.getResident().getId()));
        if (!ownsPet) {
            throw new com.havita.common.exception.BusinessRuleConflictException("ACCESS_DENIED", "No tienes permiso para eliminar esta mascota.");
        }

        Property property = pet.getProperty();
        petRepository.delete(pet);

        if (property.getPetsCount() != null && property.getPetsCount() > 0) {
            property.setPetsCount(property.getPetsCount() - 1);
            propertyRepository.save(property);
        }
        log.info("Mascota eliminada por usuario: {} (Tenant: {})", petId, tenantId);
    }
}
