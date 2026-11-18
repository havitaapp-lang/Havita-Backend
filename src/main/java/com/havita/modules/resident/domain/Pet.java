package com.havita.modules.resident.domain;

import com.havita.common.domain.TenantScopedEntity;
import com.havita.modules.property.domain.Property;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "pets")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Pet extends TenantScopedEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "property_id", nullable = false)
    private Property property;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resident_id")
    private Resident resident;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Builder.Default
    @Column(name = "type", nullable = false, length = 30)
    private String type = "Perro";

    @Column(name = "breed", length = 50)
    private String breed;

    @Column(name = "color", length = 30)
    private String color;

    @Builder.Default
    @Column(name = "is_dangerous_breed", nullable = false)
    private Boolean isDangerousBreed = false;

    @Column(name = "vaccination_card_url", length = 500)
    private String vaccinationCardUrl;

    @Column(name = "photo_url", length = 500)
    private String photoUrl;
}
