package com.doo.service;

import com.doo.domain.*;
import com.doo.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * Task management service: create, retrieve, cancel, and history.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskService {

    private final TaskRepository taskRepository;
    private final ApprovalRequestRepository approvalRepository;
    private final EvidenceArtifactRepository evidenceRepository;
    private final TaskResultRepository taskResultRepository;
    private final TaskStepRepository taskStepRepository;
    private final TaskExecutorService taskExecutorService;

    @Value("${doo.task.stale-running-minutes:15}")
    private int staleRunningMinutes;

    @Transactional
    public Task createTask(String userRequest, String idempotencyKey) {
        // Idempotency check
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            return taskRepository.findByIdempotencyKey(idempotencyKey)
                .orElseGet(() -> createAndStartTask(userRequest, idempotencyKey));
        }
        return createAndStartTask(userRequest, idempotencyKey);
    }

    private Task createAndStartTask(String userRequest, String idempotencyKey) {
        Task task = Task.builder()
            .userRequest(userRequest)
            .status(TaskStatus.QUEUED)
            .workflowType(WorkflowType.UNKNOWN)
            .currentStep(0)
            .idempotencyKey(idempotencyKey)
            .build();
        task = taskRepository.save(task);

        log.info("Created task {} for request: {}", task.getId(), 
            userRequest.substring(0, Math.min(50, userRequest.length())));

        // Start async execution strictly after transaction commits
        final UUID taskId = task.getId();
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    taskExecutorService.executeTask(taskId);
                }
            });
        } else {
            taskExecutorService.executeTask(taskId);
        }

        return task;
    }

    public Task getTask(UUID taskId) {
        return taskRepository.findById(taskId)
            .orElseThrow(() -> new TaskNotFoundException(taskId));
    }

    public Page<Task> getTaskHistory(int page, int size) {
        return taskRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(page, size));
    }

    @Transactional
    public Task cancelTask(UUID taskId) {
        Task task = getTask(taskId);
        if (task.getStatus().isTerminal()) {
            throw new IllegalStateException("Task " + taskId + " is already in terminal state: " + task.getStatus());
        }
        taskExecutorService.cancelTask(taskId);
        return taskRepository.findById(taskId).orElseThrow(() -> new TaskNotFoundException(taskId));
    }

    public List<EvidenceArtifact> getEvidence(UUID taskId) {
        getTask(taskId); // existence check
        return evidenceRepository.findByTaskIdOrderByCreatedAtAsc(taskId);
    }

    public TaskResult getResult(UUID taskId) {
        getTask(taskId); // existence check
        return taskResultRepository.findByTaskId(taskId).orElse(null);
    }

    public List<TaskStep> getSteps(UUID taskId) {
        getTask(taskId); // existence check
        return taskStepRepository.findByTaskIdOrderByStepIndexAsc(taskId);
    }

    @Transactional
    public ApprovalRequest respondToApproval(UUID taskId, UUID approvalId, boolean approved, String userResponse) {
        ApprovalRequest approval = approvalRepository.findById(approvalId)
            .orElseThrow(() -> new IllegalArgumentException("Approval " + approvalId + " not found"));

        if (!approval.getTaskId().equals(taskId)) {
            throw new IllegalArgumentException("Approval does not belong to task " + taskId);
        }
        if (!"PENDING".equals(approval.getStatus())) {
            throw new IllegalStateException("Approval is already resolved: " + approval.getStatus());
        }
        if (approval.isExpired()) {
            approval.setStatus("EXPIRED");
            approvalRepository.save(approval);
            throw new IllegalStateException("Approval has expired");
        }

        approval.setStatus(approved ? "APPROVED" : "REJECTED");
        approval.setUserResponse(userResponse);
        approval.setResolvedAt(Instant.now());
        return approvalRepository.save(approval);
    }

    /**
     * Scheduled: recover stale RUNNING tasks after application restart or worker crash.
     */
    @Scheduled(fixedDelayString = "PT5M", initialDelayString = "PT1M")
    @Transactional
    public void recoverStaleTasks() {
        Instant threshold = Instant.now().minus(staleRunningMinutes, ChronoUnit.MINUTES);
        int count = taskRepository.markStaleTasksFailed(threshold, Instant.now());
        if (count > 0) {
            log.warn("Recovered {} stale RUNNING tasks as FAILED", count);
        }
    }

    public static class TaskNotFoundException extends RuntimeException {
        public TaskNotFoundException(UUID taskId) {
            super("Task not found: " + taskId);
        }
    }
}
