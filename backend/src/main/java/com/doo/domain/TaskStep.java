package com.doo.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "task_steps")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TaskStep {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "task_id", nullable = false)
    private UUID taskId;

    @Column(name = "step_index", nullable = false)
    private int stepIndex;

    @Column(name = "action_type", nullable = false, length = 64)
    private String actionType;

    @Convert(converter = JsonConverters.MapConverter.class)
    @Column(name = "action_args", columnDefinition = "TEXT")
    private Map<String, Object> actionArgs;

    @Column(name = "result", columnDefinition = "TEXT")
    private String result;

    @Column(name = "status", length = 32)
    private String status;

    @Column(name = "error", columnDefinition = "TEXT")
    private String error;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
        if (status == null) status = "PENDING";
    }
}
