package com.havita.modules.property.domain;

import com.havita.modules.property.domain.PropertyStatus;
import com.havita.common.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.math.BigDecimal;



@Entity
@Table(name = "properties", uniqueConstraints = {
        @UniqueConstraint(name = "uq_tenant_property_number", columnNames = {"tenant_id", "number"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Property extends TenantScopedEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "structure_id")
    private Structure structure;

    @Column(name = "number", nullable = false, length = 50)
    private String number;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "path", length = 200)
    private String path;

    @Builder.Default
    @Column(name = "type", nullable = false, length = 50)
    private String type = "Apartamento";

    
    @Column(name = "status", nullable = false, length = 30)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private PropertyStatus status = PropertyStatus.VACANT;

    @Column(name = "level", length = 20)
    private String level;

    @Column(name = "area", precision = 10, scale = 2)
    private BigDecimal area;

    @Builder.Default
    @Column(name = "coefficient", precision = 8, scale = 6)
    private BigDecimal coefficient = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "bedrooms")
    private Integer bedrooms = 0;

    @Builder.Default
    @Column(name = "bathrooms")
    private Integer bathrooms = 0;

    @Builder.Default
    @Column(name = "vehicles_count")
    private Integer vehiclesCount = 0;

    @Builder.Default
    @Column(name = "pets_count")
    private Integer petsCount = 0;
}
