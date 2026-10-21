package com.doo.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcType;
import org.hibernate.dialect.PostgreSQLEnumJdbcType;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "tasks")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Task {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_request", nullable = false, columnDefinition = "TEXT")
    private String userRequest;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private TaskStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "workflow_type", nullable = false, length = 32)
    private WorkflowType workflowType;

    @Column(name = "plan", columnDefinition = "TEXT")
    private String plan;

    @Column(name = "current_step")
    private int currentStep;

    @Column(name = "success_criteria", columnDefinition = "TEXT")
    private String successCriteria;

    @Column(name = "failure_reason", columnDefinition = "TEXT")
    private String failureReason;

    @Column(name = "idempotency_key", unique = true)
    private String idempotencyKey;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
        if (updatedAt == null) updatedAt = Instant.now();
        if (status == null) status = TaskStatus.QUEUED;
        if (workflowType == null) workflowType = WorkflowType.UNKNOWN;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    /**
     * Attempt a status transition. Throws IllegalStateException if the transition is not allowed.
     */
    public void transitionTo(TaskStatus next) {
        if (!this.status.canTransitionTo(next)) {
            throw new IllegalStateException(
                String.format("Cannot transition task %s from %s to %s", id, this.status, next));
        }
        this.status = next;
        if (next == TaskStatus.RUNNING && this.startedAt == null) {
            this.startedAt = Instant.now();
        }
        if (next.isTerminal()) {
            this.completedAt = Instant.now();
        }
    }
}
