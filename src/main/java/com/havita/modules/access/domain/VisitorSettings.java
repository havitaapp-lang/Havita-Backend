package com.havita.modules.access.domain;

import com.havita.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "visitor_settings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VisitorSettings extends BaseEntity {

    @Column(name = "tenant_id", nullable = false, unique = true)
    private UUID tenantId;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "mode", nullable = false, length = 20)
    private VisitorScheduleMode mode = VisitorScheduleMode.HOURS_24;

    @Builder.Default
    @OneToMany(mappedBy = "visitorSettings", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    private List<VisitorDaySchedule> daySchedules = new ArrayList<>();
}
