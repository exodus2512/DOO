package com.doo.api;

import com.doo.domain.*;
import com.doo.events.TaskEventPublisher;
import com.doo.repository.ApprovalRequestRepository;
import com.doo.service.TaskService;
import com.doo.service.TaskService.TaskNotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Main REST API for task management.
 */
@Slf4j
@RestController
@RequestMapping("/api/tasks")
@RequiredArgsConstructor
@CrossOrigin(origins = {"http://localhost:3000"}, allowCredentials = "false")
public class TaskController {

    private final TaskService taskService;
    private final TaskEventPublisher eventPublisher;
    private final ApprovalRequestRepository approvalRepository;

    // ─── Task CRUD ─────────────────────────────────────────────────────────────

    @PostMapping
    public ResponseEntity<TaskResponse> createTask(
        @Valid @RequestBody CreateTaskRequest req,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        Task task = taskService.createTask(req.userRequest(), idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(TaskResponse.from(task));
    }

    @GetMapping("/{taskId}")
    public ResponseEntity<TaskResponse> getTask(@PathVariable UUID taskId) {
        return ResponseEntity.ok(TaskResponse.from(taskService.getTask(taskId)));
    }

    @GetMapping
    public ResponseEntity<PagedResponse<TaskResponse>> listTasks(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        Page<Task> tasks = taskService.getTaskHistory(page, Math.min(size, 100));
        List<TaskResponse> items = tasks.getContent().stream().map(TaskResponse::from).toList();
        return ResponseEntity.ok(new PagedResponse<>(items, tasks.getTotalElements(), page, size));
    }

    @PostMapping("/{taskId}/cancel")
    public ResponseEntity<TaskResponse> cancelTask(@PathVariable UUID taskId) {
        Task task = taskService.cancelTask(taskId);
        return ResponseEntity.ok(TaskResponse.from(task));
    }

    // ─── Evidence & Results ────────────────────────────────────────────────────

    @GetMapping("/{taskId}/evidence")
    public ResponseEntity<List<EvidenceResponse>> getEvidence(@PathVariable UUID taskId) {
        List<EvidenceArtifact> evidence = taskService.getEvidence(taskId);
        return ResponseEntity.ok(evidence.stream().map(EvidenceResponse::from).toList());
    }

    @GetMapping("/{taskId}/result")
    public ResponseEntity<TaskResultResponse> getResult(@PathVariable UUID taskId) {
        taskService.getTask(taskId);
        TaskResult result = taskService.getResult(taskId);
        if (result == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(TaskResultResponse.from(result));
    }

    @GetMapping("/{taskId}/steps")
    public ResponseEntity<List<TaskStepResponse>> getSteps(@PathVariable UUID taskId) {
        List<TaskStep> steps = taskService.getSteps(taskId);
        return ResponseEntity.ok(steps.stream().map(TaskStepResponse::from).toList());
    }

    // ─── Approvals ─────────────────────────────────────────────────────────────

    @GetMapping("/{taskId}/approvals/pending")
    public ResponseEntity<List<ApprovalResponse>> getPendingApprovals(@PathVariable UUID taskId) {
        List<ApprovalRequest> approvals = approvalRepository.findByTaskIdAndStatus(taskId, "PENDING");
        return ResponseEntity.ok(approvals.stream().map(ApprovalResponse::from).toList());
    }

    @PostMapping("/{taskId}/approvals/{approvalId}")
    public ResponseEntity<ApprovalResponse> respondToApproval(
        @PathVariable UUID taskId,
        @PathVariable UUID approvalId,
        @Valid @RequestBody ApprovalResponseRequest req
    ) {
        ApprovalRequest approval = taskService.respondToApproval(taskId, approvalId, req.approved(), req.userResponse());
        return ResponseEntity.ok(ApprovalResponse.from(approval));
    }

    // ─── SSE Stream ────────────────────────────────────────────────────────────

    @GetMapping(value = "/{taskId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamTaskEvents(@PathVariable UUID taskId) {
        try {
            taskService.getTask(taskId);
        } catch (TaskNotFoundException e) {
            SseEmitter emitter = new SseEmitter(1000L);
            try {
                emitter.send(SseEmitter.event().name("error").data(Map.of("code", "NOT_FOUND", "message", e.getMessage())));
                emitter.complete();
            } catch (Exception ignored) {
                emitter.complete();
            }
            return emitter;
        }
        return eventPublisher.subscribe(taskId);
    }

    // ─── Health ────────────────────────────────────────────────────────────────

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        String aiProvider = System.getenv("AI_PROVIDER");
        boolean hasKey = Stream.of("GEMINI_API_KEY_1", "GEMINI_API_KEY_2", "GEMINI_API_KEY_3")
            .anyMatch(k -> { String v = System.getenv(k); return v != null && !v.isBlank(); });
        boolean isDemo = "demo".equalsIgnoreCase(aiProvider)
            || "true".equalsIgnoreCase(System.getenv("DEMO_MODE"))
            || !hasKey;
        return ResponseEntity.ok(Map.of(
            "status", "ok",
            "service", "doo-backend",
            "aiMode", isDemo ? "demo" : "live"
        ));
    }

    // ─── Request/Response Records ──────────────────────────────────────────────

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record CreateTaskRequest(
        @NotBlank(message = "userRequest must not be blank")
        @Size(min = 10, max = 5000, message = "Request must be 10-5000 characters")
        String userRequest
    ) {}

    public record ApprovalResponseRequest(
        boolean approved,
        String userResponse
    ) {}

    public record TaskResponse(
        String id,
        String userRequest,
        String status,
        String workflowType,
        String plan,
        int currentStep,
        String successCriteria,
        String failureReason,
        String createdAt,
        String updatedAt,
        String startedAt,
        String completedAt
    ) {
        public static TaskResponse from(Task t) {
            return new TaskResponse(
                t.getId() != null ? t.getId().toString() : null,
                t.getUserRequest(),
                t.getStatus() != null ? t.getStatus().name() : "QUEUED",
                t.getWorkflowType() != null ? t.getWorkflowType().name() : "UNKNOWN",
                t.getPlan(),
                t.getCurrentStep(),
                t.getSuccessCriteria(),
                t.getFailureReason(),
                t.getCreatedAt() != null ? t.getCreatedAt().toString() : null,
                t.getUpdatedAt() != null ? t.getUpdatedAt().toString() : null,
                t.getStartedAt() != null ? t.getStartedAt().toString() : null,
                t.getCompletedAt() != null ? t.getCompletedAt().toString() : null
            );
        }
    }

    public record EvidenceResponse(
        String id,
        String taskId,
        String artifactType,
        String label,
        String content,
        String sourceUrl,
        String createdAt
    ) {
        public static EvidenceResponse from(EvidenceArtifact e) {
            return new EvidenceResponse(
                e.getId().toString(),
                e.getTaskId().toString(),
                e.getArtifactType(),
                e.getLabel(),
                e.getContent(),
                e.getSourceUrl(),
                e.getCreatedAt() != null ? e.getCreatedAt().toString() : null
            );
        }
    }

    public record TaskResultResponse(
        String id,
        String taskId,
        String summary,
        Boolean verificationPassed,
        String verificationNotes,
        List<TaskResult.SourceRef> sources,
        String createdAt
    ) {
        public static TaskResultResponse from(TaskResult r) {
            return new TaskResultResponse(
                r.getId().toString(),
                r.getTaskId().toString(),
                r.getSummary(),
                r.getVerificationPassed(),
                r.getVerificationNotes(),
                r.getSources(),
                r.getCreatedAt() != null ? r.getCreatedAt().toString() : null
            );
        }
    }

    public record TaskStepResponse(
        String id,
        String taskId,
        int stepIndex,
        String actionType,
        Map<String, Object> actionArgs,
        String result,
        String status,
        String error,
        String createdAt,
        String completedAt
    ) {
        public static TaskStepResponse from(TaskStep s) {
            return new TaskStepResponse(
                s.getId().toString(),
                s.getTaskId().toString(),
                s.getStepIndex(),
                s.getActionType(),
                s.getActionArgs(),
                s.getResult(),
                s.getStatus(),
                s.getError(),
                s.getCreatedAt() != null ? s.getCreatedAt().toString() : null,
                s.getCompletedAt() != null ? s.getCompletedAt().toString() : null
            );
        }
    }

    public record ApprovalResponse(
        String id,
        String taskId,
        String prompt,
        String proposedAction,
        Map<String, Object> parameters,
        String status,
        String userResponse,
        String createdAt,
        String expiresAt,
        String resolvedAt
    ) {
        public static ApprovalResponse from(ApprovalRequest a) {
            return new ApprovalResponse(
                a.getId().toString(),
                a.getTaskId().toString(),
                a.getPrompt(),
                a.getProposedAction(),
                a.getParameters(),
                a.getStatus(),
                a.getUserResponse(),
                a.getCreatedAt() != null ? a.getCreatedAt().toString() : null,
                a.getExpiresAt() != null ? a.getExpiresAt().toString() : null,
                a.getResolvedAt() != null ? a.getResolvedAt().toString() : null
            );
        }
    }

    public record PagedResponse<T>(List<T> items, long total, int page, int size) {}
}
