package com.doo.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "approval_requests")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ApprovalRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "task_id", nullable = false)
    private UUID taskId;

    @Column(name = "prompt", nullable = false, columnDefinition = "TEXT")
    private String prompt;

    @Column(name = "proposed_action", columnDefinition = "TEXT")
    private String proposedAction;

    @Convert(converter = JsonConverters.MapConverter.class)
    @Column(name = "parameters", columnDefinition = "TEXT")
    private Map<String, Object> parameters;

    @Column(name = "status", length = 32, nullable = false)
    private String status; // PENDING, APPROVED, REJECTED, EXPIRED

    @Column(name = "user_response", columnDefinition = "TEXT")
    private String userResponse;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
        if (expiresAt == null) expiresAt = createdAt.plusSeconds(600); // 10 min
        if (status == null) status = "PENDING";
    }

    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }
}
