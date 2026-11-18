package com.havita.modules.staff.domain;

import com.havita.common.domain.TenantScopedEntity;
import com.havita.modules.identity.domain.User;
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

import java.time.LocalDate;

@Entity
@Table(name = "staff_members", uniqueConstraints = {
        @UniqueConstraint(name = "uq_tenant_staff_document", columnNames = {"tenant_id", "document_number"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StaffMember extends TenantScopedEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(name = "first_name", nullable = false, length = 100)
    private String firstName;

    @Column(name = "last_name", nullable = false, length = 100)
    private String lastName;

    @Builder.Default
    @Column(name = "document_type", nullable = false, length = 20)
    private String documentType = "CC";

    @Column(name = "document_number", nullable = false, length = 50)
    private String documentNumber;

    @Column(name = "phone", length = 50)
    private String phone;

    @Column(name = "email", length = 150)
    private String email;

    @Column(name = "position", nullable = false, length = 100)
    private String position;

    @Builder.Default
    @Column(name = "area", nullable = false, length = 50)
    private String area = "Portería";

    @Builder.Default
    @Column(name = "contract_type", nullable = false, length = 50)
    private String contractType = "Empleado";

    @Column(name = "company", length = 150)
    private String company;

    @Builder.Default
    @Column(name = "start_date", nullable = false)
    private LocalDate startDate = LocalDate.now();

    
    @Column(name = "status", nullable = false, length = 30)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private StaffMemberStatus status = StaffMemberStatus.ACTIVE;

    @Column(name = "assigned_gate", length = 100)
    private String assignedGate;

    @Column(name = "avatar_url", length = 500)
    private String avatarUrl;

    @Column(name = "observations", columnDefinition = "TEXT")
    private String observations;

    public String getFullName() {
        return firstName + " " + lastName;
    }
}
