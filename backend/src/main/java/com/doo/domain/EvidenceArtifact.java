package com.doo.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "evidence_artifacts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EvidenceArtifact {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "task_id", nullable = false)
    private UUID taskId;

    @Column(name = "artifact_type", nullable = false, length = 32)
    private String artifactType; // SCREENSHOT, URL_VISITED, TEXT_SNIPPET, VERIFICATION_RESULT

    @Column(name = "label", length = 255)
    private String label;

    @Column(name = "content", columnDefinition = "TEXT")
    private String content;

    @Column(name = "source_url", columnDefinition = "TEXT")
    private String sourceUrl;

    @Column(name = "created_at")
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
