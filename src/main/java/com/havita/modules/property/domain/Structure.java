package com.havita.modules.property.domain;

import com.havita.common.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "structures", uniqueConstraints = {
        @UniqueConstraint(name = "uq_tenant_structure_name", columnNames = {"tenant_id", "name"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Structure extends TenantScopedEntity {

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Builder.Default
    @Column(name = "type", nullable = false, length = 50)
    private String type = "Torre";

    @Builder.Default
    @Column(name = "total_levels", nullable = false)
    private Integer totalLevels = 1;

    @Column(name = "description", length = 255)
    private String description;
}
