package com.doo.service;

import com.doo.ai.AiCallStats;
import com.doo.ai.AiProvider;
import com.doo.ai.GeminiAiProvider;
import com.doo.domain.*;
import com.doo.events.TaskEventPublisher;
import com.doo.events.TaskEventPublisher.TaskEvent;
import com.doo.repository.*;
import com.doo.security.UrlValidator;
import com.doo.worker.BrowserWorkerClient;
import com.doo.worker.BrowserWorkerClient.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * The core agent execution loop.
 * Runs asynchronously per task: plan → observe → act → verify → complete.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskExecutorService {

    private final TaskRepository taskRepository;
    private final TaskStepRepository taskStepRepository;
    private final EvidenceArtifactRepository evidenceRepository;
    private final TaskResultRepository taskResultRepository;
    private final ApprovalRequestRepository approvalRepository;
    private final AiProvider aiProvider;
    private final BrowserWorkerClient browserWorkerClient;
    private final TaskEventPublisher eventPublisher;
    private final UrlValidator urlValidator;

    @Value("${doo.task.max-steps:30}")
    private int maxSteps;

    @Value("${doo.task.max-retries:3}")
    private int maxRetries;

    @Value("${doo.task.execution-timeout-minutes:10}")
    private int executionTimeoutMinutes;

    @Value("${doo.task.max-ai-calls:40}")
    private int maxAiCalls;

    private static final int NO_PROGRESS_WINDOW = 5;
    private static final int NO_PROGRESS_THRESHOLD = 3;
    private static final int IDENTICAL_CONSECUTIVE_LIMIT = 2;

    // Track cancelled tasks to prevent actions being dispatched after cancellation
    private final Set<UUID> cancelledTasks = ConcurrentHashMap.newKeySet();

    // Allowed action types (allowlist)
    private static final List<String> ALLOWED_ACTIONS = List.of(
        "NAVIGATE", "CLICK", "FILL", "EXTRACT_TEXT", "SCREENSHOT",
        "SCROLL", "WAIT", "DONE", "GO_BACK", "SELECT_OPTION", "PRESS_KEY"
    );

    // Actions that are NOT allowed (block harmful actions even if AI suggests them)
    private static final List<String> BLOCKED_ACTIONS = List.of(
        "EXECUTE_SCRIPT", "SHELL_COMMAND", "FILE_WRITE", "HTTP_REQUEST",
        "PURCHASE", "CHECKOUT", "SUBSCRIBE", "DELETE", "LOGIN"
    );

    @Async
    public void executeTask(UUID taskId) {
        log.info("Starting execution for task {}", taskId);
        
        Task task = null;
        for (int i = 0; i < 20; i++) {
            task = taskRepository.findById(taskId).orElse(null);
            if (task != null) break;
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        if (task == null) {
            log.error("Task {} not found for execution after waiting for commit", taskId);
            return;
        }

        String sessionId = null;
        List<String> visitedUrls = new ArrayList<>();
        List<String> textSnippets = new ArrayList<>();
        List<String> recentActions = new ArrayList<>();

        int aiCallCount = 0;

        try {
            // Transition to RUNNING
            updateTaskStatus(task, TaskStatus.RUNNING, null);

            // Step 1: Classify workflow and generate plan
            publishEvent(TaskEvent.stepUpdate(taskId, "RUNNING", "Analyzing your request...", 0, Map.of()));

            publishEvent(TaskEvent.debug(taskId, "[STEP 1/5] CLASSIFY — starting", Map.of("phase", "CLASSIFY", "keyPool", getKeyPoolState())));
            AiProvider.WorkflowClassification classification = aiProvider.classifyWorkflow(task.getUserRequest());
            aiCallCount++;
            publishEvent(TaskEvent.debug(taskId, "[STEP 1/5] CLASSIFY — result: " + classification.workflowType(), Map.of("phase", "CLASSIFY", "result", classification.workflowType())));
            task.setWorkflowType(WorkflowType.valueOf(classification.workflowType()));
            task.setSuccessCriteria(classification.successCriteria());
            taskRepository.save(task); // persist workflowType so the UI chip shows immediately
            publishEvent(TaskEvent.taskUpdated(taskId, classification.workflowType()));

            publishEvent(TaskEvent.debug(taskId, "[STEP 2/5] PLAN — generating", Map.of("phase", "PLAN", "keyPool", getKeyPoolState())));
            AiProvider.TaskPlan plan = aiProvider.generatePlan(
                task.getUserRequest(),
                classification.successCriteria(),
                new AiProvider.WorkflowContext(classification.workflowType())
            );
            aiCallCount++;
            publishEvent(TaskEvent.debug(taskId, "[STEP 2/5] PLAN — " + plan.steps().size() + " steps", Map.of("phase", "PLAN", "steps", plan.steps().size())));

            String planText = String.join("\n", plan.steps().stream()
                .map((s) -> (plan.steps().indexOf(s) + 1) + ". " + s)
                .toList());
            task.setPlan(planText);
            taskRepository.save(task);

            publishEvent(TaskEvent.stepUpdate(taskId, "RUNNING",
                "Plan created: " + plan.steps().size() + " steps", 0,
                Map.of("plan", planText)));

            // ── Routing: decide execution path based on workflow type ──
            WorkflowType wfType = task.getWorkflowType();
            log.info("Task {} routing: workflowType={}, requiresBrowser={}", taskId, wfType, wfType.requiresBrowser());
            publishEvent(TaskEvent.debug(taskId, "[ROUTING] " + wfType + " → " +
                (wfType.requiresBrowser() ? "browser" : wfType == WorkflowType.UNKNOWN ? "clarification" : "llm-only"),
                Map.of("phase", "ROUTING", "path", wfType.requiresBrowser() ? "browser" : wfType.name())));

            if (wfType == WorkflowType.UNKNOWN) {
                String reason = "Request is ambiguous — please provide more detail about what you'd like to accomplish.";
                task.setFailureReason(reason);
                TaskResult taskResult = TaskResult.builder()
                    .taskId(taskId)
                    .summary(reason)
                    .verificationPassed(false)
                    .verificationNotes("Task classified as UNKNOWN — needs clarification")
                    .sources(List.of())
                    .build();
                taskResultRepository.save(taskResult);
                updateTaskStatus(task, TaskStatus.FAILED, reason);
                publishEvent(TaskEvent.error(taskId, reason));
                return;
            }

            if (wfType == WorkflowType.LLM_ONLY) {
                publishEvent(TaskEvent.stepUpdate(taskId, "RUNNING", "Answering directly (no browser needed)...", 0, Map.of()));
                String answer = aiProvider.directAnswer(task.getUserRequest());
                aiCallCount++;
                TaskResult taskResult = TaskResult.builder()
                    .taskId(taskId)
                    .summary(answer)
                    .verificationPassed(true)
                    .verificationNotes("Answered directly by LLM — no browser interaction required")
                    .sources(List.of())
                    .build();
                taskResultRepository.save(taskResult);
                updateTaskStatus(task, TaskStatus.COMPLETED, null);
                publishEvent(TaskEvent.completed(taskId, "Answered directly without browser"));
                return;
            }

            // ── Browser path: WEB_RESEARCH, PRODUCT_COMPARISON, UI_INTERACTION ──
            // Step 2: Initialize browser session
            publishEvent(TaskEvent.stepUpdate(taskId, "RUNNING", "Launching browser...", 0, Map.of()));
            SessionResult session = browserWorkerClient.initSession(taskId);

            if (!session.success()) {
                failTask(task, "Could not launch browser: " + session.error());
                return;
            }
            sessionId = session.sessionId();
            publishEvent(TaskEvent.stepUpdate(taskId, "RUNNING", "Browser ready", 0, Map.of()));

            // Step 3: Execution loop
            String currentUrl = "";
            String pageTitle = "";
            String pageText = "";
            String accessibilityTree = "";
            int consecutiveFailures = 0;
            int observationEpoch = 0;
            String lastObservationHash = "";
            // Structured perception state — updated after each action
            String lastObservationId = null;
            List<Map<String, Object>> lastInteractiveElements = null;
            int staleRetries = 0;
            final int MAX_STALE_RETRIES = 2;
            Instant deadline = Instant.now().plus(Duration.ofMinutes(executionTimeoutMinutes));
            List<String> recentActionFingerprints = new ArrayList<>();

            for (int step = 0; step < maxSteps && !isCancelled(taskId); step++) {
                // Wall-clock budget enforcement
                if (Instant.now().isAfter(deadline)) {
                    log.warn("Task {} exceeded {}min wall-clock budget at step {}", taskId, executionTimeoutMinutes, step);
                    failTask(task, "TASK_DEADLINE_EXCEEDED: exceeded " + executionTimeoutMinutes + " minute execution budget");
                    return;
                }

                // AI call budget enforcement
                if (aiCallCount >= maxAiCalls) {
                    log.warn("Task {} exceeded AI call budget ({}) at step {}", taskId, maxAiCalls, step);
                    failTask(task, "AI_CALL_BUDGET_EXCEEDED: used " + aiCallCount + "/" + maxAiCalls + " AI calls");
                    return;
                }

                task.setCurrentStep(step);
                taskRepository.save(task);

                log.debug("Task {} step {}/{} epoch={}", taskId, step, maxSteps, observationEpoch);

                // Select next action
                publishEvent(TaskEvent.debug(taskId, "[STEP 3/5] SELECT_ACTION — step " + step + ", url=" + truncate(currentUrl, 60), Map.of("phase", "SELECT_ACTION", "step", step, "keyPool", getKeyPoolState())));
                AiProvider.ActionDecision decision = aiProvider.selectNextAction(
                    new AiProvider.ActionSelectionRequest(
                        task.getUserRequest(),
                        task.getPlan(),
                        step,
                        plan.steps(),
                        currentUrl,
                        pageTitle,
                        pageText,
                        accessibilityTree,
                        recentActions,
                        ALLOWED_ACTIONS,
                        task.getWorkflowType().name(),
                        lastObservationId,
                        lastInteractiveElements
                    )
                );
                aiCallCount++;
                publishEvent(TaskEvent.debug(taskId, "[STEP 3/5] SELECT_ACTION — chose: " + decision.actionType(), Map.of("phase", "SELECT_ACTION", "action", decision.actionType())));

                // Normalize NAVIGATE URL: add https:// if scheme is missing
                if ("NAVIGATE".equals(decision.actionType())) {
                    String rawUrl = decision.args() != null ? (String) decision.args().get("url") : null;
                    if (rawUrl != null && !rawUrl.isBlank() && !rawUrl.contains("://")) {
                        Map<String, Object> fixedArgs = new HashMap<>(decision.args());
                        fixedArgs.put("url", "https://" + rawUrl);
                        decision = new AiProvider.ActionDecision(
                            decision.actionType(), fixedArgs, decision.rationale(),
                            decision.requiresApproval(), decision.approvalReason()
                        );
                        log.info("Task {} NAVIGATE URL normalized: https://{}", taskId, rawUrl);
                    }
                }

                // Check for DONE
                if ("DONE".equals(decision.actionType())) {
                    publishEvent(TaskEvent.stepUpdate(taskId, "RUNNING",
                        "Agent completed all planned steps", step, Map.of()));
                    break;
                }

                // No-progress detection: fingerprint = actionType + sorted key args + obsHash
                String fingerprint = computeActionFingerprint(decision);
                String fingerprintWithObs = fingerprint + "@" + lastObservationHash;
                recentActionFingerprints.add(fingerprintWithObs);

                // Strict check: 2 consecutive identical actions with unchanged observation
                if (recentActionFingerprints.size() >= IDENTICAL_CONSECUTIVE_LIMIT) {
                    int sz = recentActionFingerprints.size();
                    String current = recentActionFingerprints.get(sz - 1);
                    boolean allIdentical = true;
                    for (int k = sz - IDENTICAL_CONSECUTIVE_LIMIT; k < sz; k++) {
                        if (!recentActionFingerprints.get(k).equals(current)) {
                            allIdentical = false;
                            break;
                        }
                    }
                    if (allIdentical) {
                        log.warn("Task {} stuck: identical action '{}' repeated {} times with unchanged page",
                            taskId, decision.actionType(), IDENTICAL_CONSECUTIVE_LIMIT);
                        publishEvent(TaskEvent.stepUpdate(taskId, "RUNNING",
                            "No progress: same action repeated with no page change", step, Map.of()));
                        failTask(task, "Agent made no progress: repeated '" + decision.actionType()
                            + "' action " + IDENTICAL_CONSECUTIVE_LIMIT + " times with unchanged observation");
                        return;
                    }
                }

                // Window-based check: same fingerprint 3+ times in last 5 steps (ignores obsHash for this)
                List<String> fpOnly = recentActionFingerprints.stream()
                    .map(fp -> fp.contains("@") ? fp.substring(0, fp.lastIndexOf("@")) : fp)
                    .collect(Collectors.toList());
                if (detectNoProgress(fpOnly)) {
                    log.warn("Task {} stuck: action '{}' repeated {} times in last {} steps",
                        taskId, decision.actionType(), NO_PROGRESS_THRESHOLD, NO_PROGRESS_WINDOW);
                    publishEvent(TaskEvent.stepUpdate(taskId, "RUNNING",
                        "No progress detected — agent is repeating the same action", step, Map.of()));
                    failTask(task, "Agent made no progress: repeated '" + decision.actionType()
                        + "' action " + NO_PROGRESS_THRESHOLD + " times without advancing");
                    return;
                }

                // Validate action
                if (!validateAction(decision, task)) {
                    consecutiveFailures++;
                    if (consecutiveFailures >= maxRetries) {
                        failTask(task, "Too many invalid actions proposed by AI");
                        return;
                    }
                    continue;
                }

                // Check if approval required
                if (decision.requiresApproval()) {
                    ApprovalRequest approval = requestApproval(task, decision);
                    publishEvent(TaskEvent.approvalRequired(taskId, approval.getId(),
                        decision.approvalReason(), decision.actionType()));
                    updateTaskStatus(task, TaskStatus.WAITING_FOR_USER, null);

                    ApprovalRequest resolved = waitForApproval(approval.getId(), taskId);
                    if (resolved == null || "REJECTED".equals(resolved.getStatus())) {
                        failTask(task, "Action was rejected by user or approval expired");
                        return;
                    }
                    updateTaskStatus(task, TaskStatus.RUNNING, null);
                }

                // Record step with observation epoch
                Map<String, Object> enrichedArgs = new HashMap<>(decision.args() != null ? decision.args() : Map.of());
                enrichedArgs.put("_observationEpoch", observationEpoch);
                enrichedArgs.put("_observationHash", lastObservationHash);

                TaskStep taskStep = TaskStep.builder()
                    .taskId(taskId)
                    .stepIndex(step)
                    .actionType(decision.actionType())
                    .actionArgs(enrichedArgs)
                    .status("RUNNING")
                    .build();
                taskStepRepository.save(taskStep);

                publishEvent(TaskEvent.stepUpdate(taskId, "RUNNING",
                    decision.rationale() + " → " + decision.actionType(), step, Map.of()));

                // Re-check cancellation before sending action to worker
                if (isCancelled(taskId)) {
                    log.info("Task {} cancelled before action execution at step {}", taskId, step);
                    break;
                }

                // Attach structured observation to element-index actions so the worker
                // can resolve element_index → Playwright locator and run stale checks.
                Map<String, Object> actionArgs = new HashMap<>(decision.args() != null ? decision.args() : Map.of());
                if (actionArgs.containsKey("element_index") && lastObservationId != null) {
                    actionArgs.put("observation_id", lastObservationId);
                    // Minimal observation stub for stale check: url + title are sufficient
                    actionArgs.put("_observation", Map.of(
                        "observation_id", lastObservationId,
                        "url", currentUrl,
                        "title", pageTitle,
                        "interactive_elements", lastInteractiveElements != null ? lastInteractiveElements : List.of()
                    ));
                }

                // Execute action
                ActionResult result = browserWorkerClient.executeAction(
                    taskId, sessionId, decision.actionType(), actionArgs);

                // Update step record
                taskStep.setStatus(result.success() ? "COMPLETED" : "FAILED");
                taskStep.setResult(result.result());
                taskStep.setError(result.error());
                taskStep.setCompletedAt(Instant.now());
                taskStepRepository.save(taskStep);

                // Handle screenshot
                if (result.screenshot() != null && !result.screenshot().isBlank()) {
                    String label = (String) decision.args().getOrDefault("label", "Step " + step);
                    saveEvidence(taskId, "SCREENSHOT", label, result.screenshot(), result.currentUrl());
                    publishEvent(TaskEvent.screenshot(taskId, label, result.screenshot()));
                }

                // Handle STALE_OBSERVATION: re-observe and retry the step without incrementing step counter
                if (!result.success() && result.error() != null && result.error().startsWith("STALE_OBSERVATION")) {
                    staleRetries++;
                    log.info("Task {} step {}: stale observation (retry {}/{})", taskId, step, staleRetries, MAX_STALE_RETRIES);
                    if (result.observationId() != null) {
                        lastObservationId = result.observationId();
                        lastInteractiveElements = result.interactiveElements();
                    }
                    if (staleRetries <= MAX_STALE_RETRIES) {
                        step--; // retry this slot with fresh observation
                        continue;
                    }
                    // Max stale retries hit — treat as normal failure
                    staleRetries = 0;
                }
                staleRetries = 0; // reset on non-stale result

                if (result.success()) {
                    consecutiveFailures = 0;
                    recentActions.add(decision.actionType() + ": " + decision.rationale());

                    // Update structured observation state
                    if (result.observationId() != null) {
                        lastObservationId = result.observationId();
                        lastInteractiveElements = result.interactiveElements();
                    }

                    // ── Fill-then-submit guard ──────────────────────────────
                    // After a FILL that didn't change the page (no navigation, no DOM shift),
                    // the field was filled but the form was NOT submitted. Deterministically
                    // try PRESS_KEY Enter, then fall back to CLICK on a submit button.
                    if ("FILL".equals(decision.actionType())) {
                        String postFillHash = computeObservationHash(result.currentUrl(), result.pageTitle(), result.pageText());
                        boolean pageChanged = !postFillHash.equals(lastObservationHash);
                        boolean urlChanged = result.currentUrl() != null && !result.currentUrl().equals(currentUrl);
                        if (!pageChanged && !urlChanged) {
                            log.info("Task {} fill-guard: FILL did not change page state, injecting PRESS_KEY Enter", taskId);
                            recentActions.add("PRESS_KEY: fill-guard auto-submit (Enter)");
                            ActionResult enterResult = browserWorkerClient.executeAction(
                                taskId, sessionId, "PRESS_KEY", Map.of("key", "Enter"));
                            TaskStep enterStep = TaskStep.builder()
                                .taskId(taskId).stepIndex(step).actionType("PRESS_KEY")
                                .actionArgs(Map.of("key", "Enter", "_guard", "fill-submit"))
                                .status(enterResult.success() ? "COMPLETED" : "FAILED")
                                .result(enterResult.result()).error(enterResult.error())
                                .completedAt(Instant.now()).build();
                            taskStepRepository.save(enterStep);

                            if (enterResult.success()) {
                                String postEnterHash = computeObservationHash(
                                    enterResult.currentUrl(), enterResult.pageTitle(), enterResult.pageText());
                                if (!postEnterHash.equals(postFillHash)) {
                                    log.info("Task {} fill-guard: PRESS_KEY Enter advanced page", taskId);
                                    result = enterResult;
                                } else {
                                    log.info("Task {} fill-guard: Enter had no effect, trying CLICK submit button", taskId);
                                    recentActions.add("CLICK: fill-guard auto-submit (click)");
                                    ActionResult clickResult = browserWorkerClient.executeAction(
                                        taskId, sessionId, "CLICK",
                                        Map.of("selector", "button[type='submit'], input[type='submit'], button.btn-primary, button.btn-success, button:has-text('Submit'), button:has-text('Search')",
                                               "description", "submit button"));
                                    TaskStep clickStep = TaskStep.builder()
                                        .taskId(taskId).stepIndex(step).actionType("CLICK")
                                        .actionArgs(Map.of("selector", "submit-button", "_guard", "fill-submit"))
                                        .status(clickResult.success() ? "COMPLETED" : "FAILED")
                                        .result(clickResult.result()).error(clickResult.error())
                                        .completedAt(Instant.now()).build();
                                    taskStepRepository.save(clickStep);
                                    if (clickResult.success()) {
                                        result = clickResult;
                                    }
                                }
                            }
                        }
                    }

                    // For EXTRACT_TEXT, save the action's direct result as primary evidence
                    if ("EXTRACT_TEXT".equals(decision.actionType()) && result.result() != null && result.result().length() > 50) {
                        String extractLabel = (String) decision.args().getOrDefault("label", "Extracted content");
                        String extractSnippet = result.result().substring(0, Math.min(4000, result.result().length()));
                        textSnippets.add("[" + extractLabel + "] " + extractSnippet);
                        saveEvidence(taskId, "TEXT_SNIPPET", extractLabel, extractSnippet, currentUrl);
                    }

                    // Update observation epoch on state change
                    String newObsHash = computeObservationHash(result.currentUrl(), result.pageTitle(), result.pageText());
                    if (!newObsHash.equals(lastObservationHash)) {
                        observationEpoch++;
                        lastObservationHash = newObsHash;
                    }

                    if (result.currentUrl() != null && !result.currentUrl().equals(currentUrl)) {
                        currentUrl = result.currentUrl();
                        if (!visitedUrls.contains(currentUrl)) {
                            visitedUrls.add(currentUrl);
                            saveEvidence(taskId, "URL_VISITED", result.pageTitle(), null, currentUrl);
                        }
                    }

                    if (result.pageText() != null && !result.pageText().isBlank()) {
                        pageText = result.pageText();
                        pageTitle = result.pageTitle() != null ? result.pageTitle() : "";
                        accessibilityTree = result.accessibilityTree() != null ? result.accessibilityTree() : "";

                        if (!"EXTRACT_TEXT".equals(decision.actionType()) && result.pageText().length() > 100) {
                            String snippet = result.pageText().substring(0, Math.min(2000, result.pageText().length()));
                            textSnippets.add("[" + result.pageTitle() + "] " + snippet);
                            saveEvidence(taskId, "TEXT_SNIPPET", result.pageTitle(),
                                snippet, currentUrl);
                        }
                    }
                } else {
                    consecutiveFailures++;
                    log.warn("Task {} step {} failed: {}", taskId, step, result.error());

                    // Capture fresh observation from failed action result if available
                    if (result.observationId() != null) {
                        lastObservationId = result.observationId();
                        lastInteractiveElements = result.interactiveElements();
                    }

                    if (consecutiveFailures >= maxRetries) {
                        failTask(task, "Browser action failed " + maxRetries + " times: " + result.error());
                        return;
                    }
                    BrowserState state = browserWorkerClient.getState(taskId, sessionId);
                    if (state.currentUrl() != null) {
                        currentUrl = state.currentUrl();
                        pageText = state.pageText() != null ? state.pageText() : "";
                        pageTitle = state.pageTitle() != null ? state.pageTitle() : "";
                        String newObsHash = computeObservationHash(currentUrl, pageTitle, pageText);
                        if (!newObsHash.equals(lastObservationHash)) {
                            observationEpoch++;
                            lastObservationHash = newObsHash;
                        }
                    }
                }
            }

            if (isCancelled(taskId)) {
                updateTaskStatus(task, TaskStatus.CANCELLED, null);
                publishEvent(TaskEvent.statusChange(taskId, "CANCELLED", "Task was cancelled by user"));
                return;
            }

            // Step 4: Synthesize result
            updateTaskStatus(task, TaskStatus.VERIFYING, null);
            publishEvent(TaskEvent.stepUpdate(taskId, "VERIFYING", "Synthesizing results...", maxSteps, Map.of()));

            publishEvent(TaskEvent.debug(taskId, "[STEP 4/5] SYNTHESIZE — " + visitedUrls.size() + " URLs, " + textSnippets.size() + " snippets", Map.of("phase", "SYNTHESIZE", "keyPool", getKeyPoolState())));
            String synthesized = aiProvider.synthesizeResult(
                new AiProvider.SynthesisRequest(
                    task.getUserRequest(),
                    task.getWorkflowType().name(),
                    visitedUrls,
                    textSnippets,
                    recentActions
                )
            );
            aiCallCount++;
            publishEvent(TaskEvent.debug(taskId, "[STEP 4/5] SYNTHESIZE — done", Map.of("phase", "SYNTHESIZE")));

            // Step 5: Evidence-based verification
            publishEvent(TaskEvent.stepUpdate(taskId, "VERIFYING", "Verifying outcome against evidence...", maxSteps, Map.of()));

            List<String> evidenceSummary = new ArrayList<>();
            evidenceSummary.add("Visited " + visitedUrls.size() + " URLs");
            evidenceSummary.add("Collected " + textSnippets.size() + " text snippets");
            evidenceSummary.add("Executed " + recentActions.size() + " actions");
            evidenceSummary.add("Observation epochs: " + observationEpoch);
            if (!visitedUrls.isEmpty()) {
                evidenceSummary.add("Final URL: " + currentUrl);
            }

            publishEvent(TaskEvent.debug(taskId, "[STEP 5/5] VERIFY — checking evidence", Map.of("phase", "VERIFY", "keyPool", getKeyPoolState())));
            AiProvider.VerificationResult verification = aiProvider.verify(
                new AiProvider.VerificationRequest(
                    task.getUserRequest(),
                    task.getSuccessCriteria(),
                    task.getWorkflowType().name(),
                    currentUrl,
                    pageText,
                    visitedUrls,
                    synthesized,
                    evidenceSummary
                )
            );
            aiCallCount++;
            publishEvent(TaskEvent.debug(taskId, "[STEP 5/5] VERIFY — passed=" + verification.passed() + ", confidence=" + verification.confidence(), Map.of("phase", "VERIFY", "passed", verification.passed())));

            saveEvidence(taskId, "VERIFICATION_RESULT", "Verification",
                "Passed: " + verification.passed() + "\n" + verification.notes(), null);

            // Step 6: Save result and transition to final state
            List<TaskResult.SourceRef> sources = visitedUrls.stream()
                .filter(u -> u != null && !u.isBlank())
                .map(u -> new TaskResult.SourceRef(u, ""))
                .toList();

            TaskResult taskResult = TaskResult.builder()
                .taskId(taskId)
                .summary(synthesized)
                .verificationPassed(verification.passed())
                .verificationNotes(verification.notes())
                .sources(sources)
                .build();
            taskResultRepository.save(taskResult);

            TaskStatus finalStatus = verification.passed() ? TaskStatus.COMPLETED : TaskStatus.PARTIAL;
            updateTaskStatus(task, finalStatus, null);
            
            publishEvent(TaskEvent.completed(taskId, 
                verification.passed() ? "Task completed successfully" : 
                "Task partially completed: " + verification.notes()));

        } catch (Exception e) {
            log.error("Unexpected error executing task {}: {}", taskId, e.getMessage(), e);
            Task freshTask = taskRepository.findById(taskId).orElse(null);
            if (freshTask != null && !freshTask.getStatus().isTerminal()) {
                failTask(freshTask, "Unexpected error: " + e.getMessage());
            }
        } finally {
            if (sessionId != null) {
                browserWorkerClient.closeSession(taskId, sessionId);
            }
            cancelledTasks.remove(taskId);

            // Log AI call stats
            String taskLabel = task != null ? truncate(task.getUserRequest(), 60) : taskId.toString();
            log.info("Task {} AI call stats: total={}, breakdown=classify/plan/action/synth/verify/retry",
                taskId, aiCallCount);
            if (aiProvider instanceof GeminiAiProvider gp) {
                AiCallStats stats = gp.getOrCreateStats(taskLabel);
                log.info("Task {} model call breakdown: {}", taskId, stats.snapshot());
                gp.clearStats(taskLabel);
            }
        }
    }

    public void cancelTask(UUID taskId) {
        cancelledTasks.add(taskId);
        browserWorkerClient.cancelTask(taskId);
        
        Task task = taskRepository.findById(taskId).orElse(null);
        if (task != null && !task.getStatus().isTerminal()) {
            // Mark as CANCELLED (may be overridden by the running thread shortly)
            try {
                task.transitionTo(TaskStatus.CANCELLED);
                taskRepository.save(task);
                publishEvent(TaskEvent.statusChange(taskId, "CANCELLED", "Cancellation requested"));
            } catch (IllegalStateException e) {
                log.warn("Could not cancel task {}: {}", taskId, e.getMessage());
            }
        }
    }

    private boolean isCancelled(UUID taskId) {
        if (cancelledTasks.contains(taskId)) return true;
        // Also re-check DB in case of external cancellation
        Task task = taskRepository.findById(taskId).orElse(null);
        return task != null && task.getStatus() == TaskStatus.CANCELLED;
    }

    @Transactional
    private void updateTaskStatus(Task task, TaskStatus newStatus, String failureReason) {
        task.transitionTo(newStatus);
        if (failureReason != null) task.setFailureReason(failureReason);
        taskRepository.save(task);
        publishEvent(TaskEvent.statusChange(task.getId(), newStatus.name(),
            failureReason != null ? failureReason : newStatus.name().toLowerCase().replace("_", " ")));
    }

    private void failTask(Task task, String reason) {
        try {
            task.setFailureReason(reason);
            if (!task.getStatus().isTerminal()) {
                task.transitionTo(TaskStatus.FAILED);
            }
            taskRepository.save(task);
            publishEvent(TaskEvent.error(task.getId(), reason));
        } catch (Exception e) {
            log.error("Could not mark task {} as failed: {}", task.getId(), e.getMessage());
        }
    }

    private boolean validateAction(AiProvider.ActionDecision decision, Task task) {
        if (decision == null || decision.actionType() == null) return false;
        
        String actionType = decision.actionType().toUpperCase();
        
        if (BLOCKED_ACTIONS.contains(actionType)) {
            log.warn("Task {} proposed blocked action: {}", task.getId(), actionType);
            publishEvent(TaskEvent.stepUpdate(task.getId(), "RUNNING",
                "Skipped disallowed action: " + actionType, task.getCurrentStep(), Map.of()));
            return false;
        }
        
        if (!ALLOWED_ACTIONS.contains(actionType)) {
            log.warn("Task {} proposed unknown action: {}", task.getId(), actionType);
            return false;
        }

        // Validate NAVIGATE URL — blank or invalid URL is a hard reject
        if ("NAVIGATE".equals(actionType)) {
            String url = decision.args() != null ? (String) decision.args().get("url") : null;
            if (url == null || url.isBlank()) {
                log.warn("Task {} proposed NAVIGATE with no URL", task.getId());
                publishEvent(TaskEvent.stepUpdate(task.getId(), "RUNNING",
                    "NAVIGATE rejected: no URL provided", task.getCurrentStep(), Map.of()));
                return false;
            }
            try {
                urlValidator.validate(url);
            } catch (SecurityException e) {
                log.warn("Task {} proposed invalid navigation URL '{}': {}", task.getId(), url, e.getMessage());
                publishEvent(TaskEvent.stepUpdate(task.getId(), "RUNNING",
                    "NAVIGATE rejected: " + e.getMessage(), task.getCurrentStep(), Map.of()));
                return false;
            }
        }

        return true;
    }

    private ApprovalRequest requestApproval(Task task, AiProvider.ActionDecision decision) {
        ApprovalRequest approval = ApprovalRequest.builder()
            .taskId(task.getId())
            .prompt(decision.approvalReason())
            .proposedAction(decision.actionType())
            .parameters(decision.args())
            .status("PENDING")
            .build();
        return approvalRepository.save(approval);
    }

    private ApprovalRequest waitForApproval(UUID approvalId, UUID taskId) {
        int maxWaitSeconds = 600; // 10 minutes
        int pollIntervalMs = 2000;
        int waited = 0;

        while (waited < maxWaitSeconds * 1000) {
            if (isCancelled(taskId)) return null;
            
            try { Thread.sleep(pollIntervalMs); } catch (InterruptedException e) { return null; }
            waited += pollIntervalMs;

            ApprovalRequest approval = approvalRepository.findById(approvalId).orElse(null);
            if (approval == null) return null;
            if ("APPROVED".equals(approval.getStatus()) || "REJECTED".equals(approval.getStatus())) {
                return approval;
            }
            if (approval.isExpired()) {
                approval.setStatus("EXPIRED");
                approvalRepository.save(approval);
                return approval;
            }
        }
        return null;
    }

    private void saveEvidence(UUID taskId, String type, String label, String content, String sourceUrl) {
        try {
            // Skip empty content
            if ("TEXT_SNIPPET".equals(type) && (content == null || content.isBlank())) return;
            // Limit screenshot size
            if ("SCREENSHOT".equals(type) && content != null && content.length() > 500_000) {
                content = content.substring(0, 500_000); // ~375KB base64 = ~280KB image
            }

            EvidenceArtifact evidence = EvidenceArtifact.builder()
                .taskId(taskId)
                .artifactType(type)
                .label(label)
                .content(content)
                .sourceUrl(sourceUrl)
                .build();
            evidenceRepository.save(evidence);
        } catch (Exception e) {
            log.warn("Could not save evidence for task {}: {}", taskId, e.getMessage());
        }
    }

    private String computeActionFingerprint(AiProvider.ActionDecision decision) {
        String type = decision.actionType();
        Map<String, Object> args = decision.args() != null ? decision.args() : Map.of();
        // Exclude internal/transient keys: _ prefix and observation_id (changes every step)
        String keyArgs = args.entrySet().stream()
            .filter(e -> !e.getKey().startsWith("_") && !"observation_id".equals(e.getKey()))
            .sorted(Map.Entry.comparingByKey())
            .map(e -> e.getKey() + "=" + e.getValue())
            .reduce((a, b) -> a + "&" + b)
            .orElse("");
        return type + "|" + keyArgs;
    }

    private boolean detectNoProgress(List<String> fingerprints) {
        if (fingerprints.size() < NO_PROGRESS_THRESHOLD) return false;
        int windowStart = Math.max(0, fingerprints.size() - NO_PROGRESS_WINDOW);
        List<String> window = fingerprints.subList(windowStart, fingerprints.size());
        Map<String, Integer> counts = new HashMap<>();
        for (String fp : window) {
            int count = counts.merge(fp, 1, Integer::sum);
            if (count >= NO_PROGRESS_THRESHOLD) return true;
        }
        return false;
    }

    private String computeObservationHash(String url, String title, String text) {
        String input = (url != null ? url : "") + "|"
            + (title != null ? title : "") + "|"
            + (text != null ? text.substring(0, Math.min(500, text.length())) : "");
        return Integer.toHexString(input.hashCode());
    }

    private String truncate(String text, int maxLength) {
        if (text == null) return "";
        return text.length() > maxLength ? text.substring(0, maxLength) : text;
    }

    private void publishEvent(TaskEvent event) {
        try {
            eventPublisher.publish(event);
        } catch (Exception e) {
            log.debug("Event publish failed: {}", e.getMessage());
        }
    }

    private String getKeyPoolState() {
        if (aiProvider instanceof GeminiAiProvider gp) {
            return gp.getKeyPool().getKeyStates().toString();
        }
        return "N/A";
    }
}
