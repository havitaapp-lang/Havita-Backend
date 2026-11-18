package com.havita.modules.assembly.domain;

import com.havita.common.domain.TenantScopedEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "polls")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Poll extends TenantScopedEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "assembly_id", nullable = false)
    private Assembly assembly;

    @Column(name = "question", nullable = false, length = 300)
    private String question;

    @Builder.Default
    @Column(name = "type", nullable = false, length = 30)
    private String type = "SINGLE_CHOICE"; // SINGLE_CHOICE, MULTIPLE_CHOICE

    
    @Column(name = "status", nullable = false, length = 30)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private PollStatus status = PollStatus.DRAFT; // DRAFT, SCHEDULED, LIVE, CLOSED

    @Builder.Default
    @Column(name = "show_results_to_residents", nullable = false)
    private Boolean showResultsToResidents = true;

    @Column(name = "duration_minutes")
    private Integer durationMinutes;

    @Column(name = "opened_at")
    private OffsetDateTime openedAt;

    @Column(name = "closed_at")
    private OffsetDateTime closedAt;

    @Builder.Default
    @OneToMany(mappedBy = "poll", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("optionOrder ASC")
    private List<PollOption> options = new ArrayList<>();

    public void open() {
        this.status = PollStatus.LIVE;
        this.openedAt = OffsetDateTime.now();
    }

    public void close() {
        this.status = PollStatus.CLOSED;
        this.closedAt = OffsetDateTime.now();
    }
}
