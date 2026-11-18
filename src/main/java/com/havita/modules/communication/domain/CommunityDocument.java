package com.havita.modules.communication.domain;

import com.havita.common.domain.TenantScopedEntity;
import com.havita.modules.identity.domain.User;
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
@Table(name = "community_documents")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CommunityDocument extends TenantScopedEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "uploaded_by_user_id")
    private User uploadedByUser;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Builder.Default
    @Column(name = "category", nullable = false, length = 50)
    private String category = "General"; // Reglamento, Acta, Estados Financieros, Manual, Circular

    @Builder.Default
    @Column(name = "file_type", nullable = false, length = 20)
    private String fileType = "PDF"; // PDF, DOCX, XLSX

    @Column(name = "file_size", nullable = false, length = 50)
    private String fileSize;

    @Column(name = "file_url", nullable = false, length = 500)
    private String fileUrl;

    @Column(name = "storage_key", length = 500)
    private String storageKey;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Builder.Default
    @Column(name = "downloads_count", nullable = false)
    private Integer downloadsCount = 0;

    public void incrementDownloads() {
        this.downloadsCount = (this.downloadsCount != null ? this.downloadsCount : 0) + 1;
    }
}
