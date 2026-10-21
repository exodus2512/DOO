package com.doo.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "task_results")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TaskResult {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "task_id", nullable = false, unique = true)
    private UUID taskId;

    @Column(name = "summary", columnDefinition = "TEXT")
    private String summary;

    @Column(name = "verification_passed")
    private Boolean verificationPassed;

    @Column(name = "verification_notes", columnDefinition = "TEXT")
    private String verificationNotes;

    @Convert(converter = JsonConverters.SourceRefListConverter.class)
    @Column(name = "sources", columnDefinition = "TEXT")
    private List<SourceRef> sources;

    @Column(name = "created_at")
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public record SourceRef(String url, String title) {}
}
