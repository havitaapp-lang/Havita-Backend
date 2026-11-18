package com.havita.modules.organization.application;

import com.havita.common.exception.BusinessRuleConflictException;
import com.havita.common.exception.ResourceNotFoundException;
import com.havita.modules.organization.api.dto.CreateOrganizationRequest;
import com.havita.modules.organization.api.dto.OrganizationResponse;
import com.havita.modules.organization.api.dto.UpdateOrganizationRequest;
import com.havita.modules.organization.domain.Organization;
import com.havita.modules.organization.infrastructure.OrganizationRepository;
import com.havita.modules.organization.infrastructure.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrganizationService {

    private final OrganizationRepository organizationRepository;
    private final TenantRepository tenantRepository;

    @Transactional(readOnly = true)
    public Page<OrganizationResponse> findAll(String search, Pageable pageable) {
        Page<Organization> page;
        if (search != null && !search.isBlank()) {
            page = organizationRepository.searchByNameOrNit(search.trim(), pageable);
        } else {
            page = organizationRepository.findAll(pageable);
        }
        return page.map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public OrganizationResponse findById(UUID id) {
        Organization org = organizationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Organization", id));
        return toResponse(org);
    }

    @Transactional
    public OrganizationResponse create(CreateOrganizationRequest request) {
        if (organizationRepository.existsByNit(request.nit())) {
            throw new BusinessRuleConflictException(
                    "ORGANIZATION_NIT_EXISTS",
                    "Ya existe una organización registrada con el NIT " + request.nit());
        }

        Organization org = Organization.builder()
                .name(request.name())
                .nit(request.nit())
                .email(request.email())
                .phone(request.phone())
                .city(request.city())
                .address(request.address())
                .build();

        org = organizationRepository.save(org);
        log.info("Organización creada: {} (NIT: {})", org.getName(), org.getNit());
        return toResponse(org);
    }

    @Transactional
    public OrganizationResponse update(UUID id, UpdateOrganizationRequest request) {
        Organization org = organizationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Organization", id));

        if (request.name() != null) org.setName(request.name());
        if (request.email() != null) org.setEmail(request.email());
        if (request.phone() != null) org.setPhone(request.phone());
        if (request.city() != null) org.setCity(request.city());
        if (request.address() != null) org.setAddress(request.address());
        if (request.status() != null) org.setStatus(request.status());

        org = organizationRepository.save(org);
        log.info("Organización actualizada: {}", org.getId());
        return toResponse(org);
    }

    private OrganizationResponse toResponse(Organization org) {
        long communitiesCount = tenantRepository.countByOrganizationId(org.getId());
        return new OrganizationResponse(
                org.getId(),
                org.getName(),
                org.getNit(),
                org.getEmail(),
                org.getPhone(),
                org.getCity(),
                org.getAddress(),
                org.getLogoUrl(),
                org.getStatus(),
                communitiesCount,
                org.getCreatedAt(),
                org.getUpdatedAt()
        );
    }
}
