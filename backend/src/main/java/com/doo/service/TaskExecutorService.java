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
    private final CredentialVaultService credentialVaultService;

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
    private static final int MAX_REPLANS = 2;

    // Track cancelled tasks to prevent actions being dispatched after cancellation
    private final Set<UUID> cancelledTasks = ConcurrentHashMap.newKeySet();

    // Allowed action types (allowlist)
    private static final List<String> ALLOWED_ACTIONS = List.of(
        "NAVIGATE", "CLICK", "FILL", "EXTRACT_TEXT", "SCREENSHOT",
        "SCROLL", "WAIT", "DONE", "GO_BACK", "SELECT_OPTION", "PRESS_KEY", "ASK_USER"
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
            task.setGoal(classification.goal());
            task.setDeliverableType(classification.deliverable());
            task.setEntities(classification.entities());
            taskRepository.save(task);
            publishEvent(TaskEvent.taskUpdated(taskId, classification.workflowType()));

            publishEvent(TaskEvent.debug(taskId, "[STEP 2/5] PLAN — generating", Map.of("phase", "PLAN", "keyPool", getKeyPoolState())));
            final AiProvider.TaskPlan[] planHolder = { aiProvider.generatePlan(
                task.getUserRequest(),
                classification.successCriteria(),
                new AiProvider.WorkflowContext(classification.workflowType())
            ) };
            aiCallCount++;
            publishEvent(TaskEvent.debug(taskId, "[STEP 2/5] PLAN — " + planHolder[0].steps().size() + " steps", Map.of("phase", "PLAN", "steps", planHolder[0].steps().size())));

            String planText = formatPlanText(planHolder[0]);
            task.setPlan(planText);
            taskRepository.save(task);

            publishEvent(TaskEvent.stepUpdate(taskId, "RUNNING",
                "Plan created: " + planHolder[0].steps().size() + " steps", 0,
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

            // ── Credential gate: detect login steps and resolve credentials ──
            // domain -> "username\npassword" (actual values for worker fill, never sent to LLM)
            Map<String, String> taskCredentials = new HashMap<>();
            // Credential hints for the AI prompt — placeholders, never actual passwords
            String credentialHints = null;
            if (wfType.requiresBrowser()) {
                CredentialGateResult credResult = checkCredentialGate(task, planHolder[0].steps(), sessionId);
                if (credResult.needsPause && !credResult.resolved) {
                    return;
                }
                taskCredentials.putAll(credResult.credentials);
                credentialHints = buildCredentialHints(taskCredentials);
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
            int resolverRetries = 0;
            final int MAX_RESOLVER_RETRIES = 2;
            int screenshotCount = 0;
            List<String> screenshotLog = new ArrayList<>();
            Map<String, Object> lastScrollInfo = null;
            Instant deadline = Instant.now().plus(Duration.ofMinutes(executionTimeoutMinutes));
            List<String> recentActionFingerprints = new ArrayList<>();
            int replanCount = 0;

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
                        planHolder[0].steps(),
                        currentUrl,
                        pageTitle,
                        pageText,
                        accessibilityTree,
                        recentActions,
                        ALLOWED_ACTIONS,
                        task.getWorkflowType().name(),
                        lastObservationId,
                        lastInteractiveElements,
                        credentialHints,
                        lastScrollInfo,
                        textSnippets.size()
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

                // ASK_USER: pause and show an ask-card built from the AI's field list
                if ("ASK_USER".equals(decision.actionType())) {
                    Map<String, Object> askArgs = decision.args() != null ? decision.args() : Map.of();
                    String askReason = (String) askArgs.getOrDefault("reason", "The agent needs additional information to proceed");
                    Object fieldsObj = askArgs.get("fields");
                    List<?> fieldsList = fieldsObj instanceof List<?> l ? l : List.of();

                    Map<String, Object> approvalParams = new HashMap<>();
                    approvalParams.put("type", "form_input_request");
                    approvalParams.put("fields", fieldsList);
                    approvalParams.put("reason", askReason);

                    ApprovalRequest approval = ApprovalRequest.builder()
                        .taskId(taskId)
                        .prompt(askReason)
                        .proposedAction("ASK_USER")
                        .parameters(approvalParams)
                        .status("PENDING")
                        .build();
                    approval = approvalRepository.save(approval);

                    log.info("Task {} — ASK_USER: {} fields requested", taskId, fieldsList.size());
                    PauseResult pauseResult = pauseForUser(task, sessionId, approval.getId(),
                        askReason, "ASK_USER", step, approvalParams);
                    if (!pauseResult.resumed) {
                        failTask(task, pauseResult.failureReason());
                        return;
                    }

                    // Parse user-provided field values and inject them into subsequent FILL actions
                    ApprovalRequest resolved = approvalRepository.findById(approval.getId()).orElse(null);
                    if (resolved != null && resolved.getUserResponse() != null) {
                        try {
                            @SuppressWarnings("unchecked")
                            Map<String, String> userValues = new com.fasterxml.jackson.databind.ObjectMapper()
                                .readValue(resolved.getUserResponse(), Map.class);
                            // Store values as credential-like hints so the model can reference them
                            StringBuilder hints = new StringBuilder("\nUser-provided values (use these for FILL):\n");
                            for (Map.Entry<String, String> entry : userValues.entrySet()) {
                                hints.append("- ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
                            }
                            credentialHints = (credentialHints != null ? credentialHints : "") + hints;
                        } catch (Exception e) {
                            log.warn("Task {} — could not parse ASK_USER response: {}", taskId, e.getMessage());
                        }
                    }

                    // Restore observation state from unpark
                    if (pauseResult.observationId != null) {
                        lastObservationId = pauseResult.observationId;
                        lastInteractiveElements = pauseResult.interactiveElements;
                    }
                    if (pauseResult.currentUrl != null) currentUrl = pauseResult.currentUrl;
                    if (pauseResult.pageTitle != null) pageTitle = pauseResult.pageTitle;
                    if (pauseResult.pageText != null) {
                        pageText = pauseResult.pageText;
                        lastObservationHash = computeObservationHash(currentUrl, pageTitle, pageText, lastInteractiveElements);
                        observationEpoch++;
                    }
                    recentActions.add("ASK_USER: user provided values for " + fieldsList.size() + " fields");
                    continue;
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
                    PauseResult pauseResult = pauseForUser(task, sessionId, approval.getId(),
                        decision.approvalReason(), decision.actionType(), step, approval.getParameters());
                    if (!pauseResult.resumed) {
                        failTask(task, pauseResult.failureReason);
                        return;
                    }
                    // Restore observation state from unpark if available
                    if (pauseResult.observationId != null) {
                        lastObservationId = pauseResult.observationId;
                        lastInteractiveElements = pauseResult.interactiveElements;
                    }
                    if (pauseResult.currentUrl != null) currentUrl = pauseResult.currentUrl;
                    if (pauseResult.pageTitle != null) pageTitle = pauseResult.pageTitle;
                    if (pauseResult.pageText != null) {
                        pageText = pauseResult.pageText;
                        lastObservationHash = computeObservationHash(currentUrl, pageTitle, pageText, lastInteractiveElements);
                        observationEpoch++;
                    }
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

                // Resolve credential placeholders in FILL values before sending to worker.
                // The model uses __cred_username:domain__ / __cred_password:domain__ placeholders;
                // actual passwords never enter the LLM prompt.
                if ("FILL".equals(decision.actionType()) && decision.args() != null) {
                    Object fillValue = decision.args().get("value");
                    if (fillValue instanceof String sv) {
                        String resolved = resolveCredentialPlaceholder(sv, taskCredentials);
                        if (!sv.equals(resolved)) {
                            Map<String, Object> patchedArgs = new HashMap<>(decision.args());
                            patchedArgs.put("value", resolved);
                            decision = new AiProvider.ActionDecision(
                                decision.actionType(), patchedArgs, decision.rationale(),
                                decision.requiresApproval(), decision.approvalReason());
                        }
                    }
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

                // Log what we're sending to the worker
                Object sentElementIndex = actionArgs.get("element_index");
                Object sentObsId = actionArgs.get("observation_id");
                if (sentElementIndex != null) {
                    log.info("Task {} step {} | DISPATCH {} | element_index={} obs_id={} lastKnownObs={}",
                        taskId, step, decision.actionType(), sentElementIndex, sentObsId, lastObservationId);
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

                // Log action result for diagnostics
                log.info("Task {} step {} | {} | success={} | obsId={} | url={}",
                    taskId, step, decision.actionType(), result.success(),
                    result.observationId() != null ? result.observationId() : "none",
                    truncate(result.currentUrl(), 60));
                if (!result.success()) {
                    log.warn("Task {} step {} | {} | FAILED: {}", taskId, step, decision.actionType(), result.error());
                }

                // Save screenshot evidence for every action (with step metadata)
                if (result.screenshot() != null && !result.screenshot().isBlank()) {
                    String screenshotLabel = "Step " + step + ": " + decision.actionType()
                        + (result.success() ? "" : " [FAILED]");
                    saveStepScreenshot(taskId, step, decision.actionType(),
                        result.observationId(), result.success(), screenshotLabel,
                        result.screenshot(), result.currentUrl());
                    publishEvent(TaskEvent.screenshot(taskId, screenshotLabel, result.screenshot()));
                    screenshotCount++;
                    screenshotLog.add("Screenshot #" + screenshotCount + ": step " + step
                        + " " + decision.actionType() + " → " + truncate(result.currentUrl(), 50)
                        + (result.success() ? " [OK]" : " [FAIL]"));
                }

                // Handle CAPTCHA/2FA challenges: pause for user, DO NOT attempt to solve
                if (result.challenges() != null && !result.challenges().isEmpty()) {
                    ChallengeHandleResult challengeResult = handleChallenges(task, sessionId, step, result);
                    if (!challengeResult.resumed) {
                        failTask(task, challengeResult.failureReason);
                        return;
                    }
                    // Restore observation state after user solved the challenge
                    if (challengeResult.observationId != null) {
                        lastObservationId = challengeResult.observationId;
                        lastInteractiveElements = challengeResult.interactiveElements;
                    }
                    if (challengeResult.currentUrl != null) currentUrl = challengeResult.currentUrl;
                    if (challengeResult.pageTitle != null) pageTitle = challengeResult.pageTitle;
                    if (challengeResult.pageText != null) {
                        pageText = challengeResult.pageText;
                        lastObservationHash = computeObservationHash(currentUrl, pageTitle, pageText, lastInteractiveElements);
                        observationEpoch++;
                    }
                    recentActions.add("CHALLENGE_RESOLVED: user completed " +
                        result.challenges().get(0).getOrDefault("type", "unknown") + " challenge");
                }

                // Handle RESOLVE_FAILED: element resolution failed; retry with fresh observation containing valid indices
                if (!result.success() && result.error() != null && result.error().contains("RESOLVE_FAILED")) {
                    resolverRetries++;
                    log.warn("Task {} step {}: [RESOLVE_FAILED] {} (retry {}/{})",
                        taskId, step, result.error(), resolverRetries, MAX_RESOLVER_RETRIES);
                    publishEvent(TaskEvent.stepUpdate(taskId, "RUNNING",
                        "[RESOLVE_FAILED] " + result.error(), step, Map.of("error", result.error())));

                    if (result.observationId() != null) {
                        lastObservationId = result.observationId();
                        lastInteractiveElements = result.interactiveElements();
                    }
                    if (result.currentUrl() != null && !result.currentUrl().isBlank()) {
                        currentUrl = result.currentUrl();
                    }
                    if (result.pageTitle() != null) pageTitle = result.pageTitle();
                    if (result.pageText() != null && !result.pageText().isBlank()) {
                        pageText = result.pageText();
                        accessibilityTree = result.accessibilityTree() != null ? result.accessibilityTree() : "";
                    }
                    String newObsHash = computeObservationHash(currentUrl, pageTitle, pageText, lastInteractiveElements);
                    if (!newObsHash.equals(lastObservationHash)) {
                        observationEpoch++;
                        lastObservationHash = newObsHash;
                    }

                    Object targetIdx = decision.args() != null ? decision.args().get("element_index") : null;
                    recentActions.add("RESOLVE_FAILED: " + decision.actionType()
                        + (targetIdx != null ? " at index " + targetIdx : "") + " failed — " + result.error());

                    if (resolverRetries <= MAX_RESOLVER_RETRIES) {
                        if (!recentActionFingerprints.isEmpty()) {
                            recentActionFingerprints.remove(recentActionFingerprints.size() - 1);
                        }
                        consecutiveFailures = 0;
                        step--; // retry this step slot with fresh observation containing valid indices
                        continue;
                    }
                    resolverRetries = 0;
                }

                if (result.success()) {
                    consecutiveFailures = 0;
                    resolverRetries = 0;
                    recentActions.add(decision.actionType() + ": " + decision.rationale());

                    // Update structured observation state
                    if (result.observationId() != null) {
                        lastObservationId = result.observationId();
                        lastInteractiveElements = result.interactiveElements();
                    }
                    if (result.scroll() != null) {
                        lastScrollInfo = result.scroll();
                    }

                    // For EXTRACT_TEXT, save the action's direct result as primary evidence
                    if ("EXTRACT_TEXT".equals(decision.actionType()) && result.result() != null && result.result().length() > 50) {
                        String extractLabel = (String) decision.args().getOrDefault("label", "Extracted content");
                        String extractSnippet = result.result().substring(0, Math.min(6000, result.result().length()));
                        textSnippets.add("[" + extractLabel + " — " + currentUrl + "] " + extractSnippet);
                        saveEvidence(taskId, "TEXT_SNIPPET", extractLabel, extractSnippet, currentUrl);
                    }

                    // Update observation epoch on state change
                    String newObsHash = computeObservationHash(result.currentUrl(), result.pageTitle(), result.pageText(), lastInteractiveElements);
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
                    recentActions.add("FAILED: " + decision.actionType() + " failed: " + result.error());

                    // Capture fresh observation from failed action result if available
                    if (result.observationId() != null) {
                        lastObservationId = result.observationId();
                        lastInteractiveElements = result.interactiveElements();
                    }
                    if (result.currentUrl() != null && !result.currentUrl().isBlank()) {
                        currentUrl = result.currentUrl();
                    }
                    if (result.pageTitle() != null) {
                        pageTitle = result.pageTitle();
                    }
                    if (result.pageText() != null && !result.pageText().isBlank()) {
                        pageText = result.pageText();
                        accessibilityTree = result.accessibilityTree() != null ? result.accessibilityTree() : "";
                    }
                    String newObsHash = computeObservationHash(currentUrl, pageTitle, pageText, lastInteractiveElements);
                    if (!newObsHash.equals(lastObservationHash)) {
                        observationEpoch++;
                        lastObservationHash = newObsHash;
                    }

                    if (consecutiveFailures >= maxRetries) {
                        if (replanCount < MAX_REPLANS) {
                            replanCount++;
                            log.info("Task {} — {} consecutive failures, attempting replan {}/{}", taskId, consecutiveFailures, replanCount, MAX_REPLANS);
                            publishEvent(TaskEvent.stepUpdate(taskId, "RUNNING",
                                "Replanning after " + consecutiveFailures + " failures...", step, Map.of()));
                            try {
                                AiProvider.TaskPlan newPlan = aiProvider.replan(new AiProvider.ReplanRequest(
                                    task.getUserRequest(), task.getWorkflowType().name(),
                                    planHolder[0].steps(), step, result.error() != null ? result.error() : "action failed",
                                    currentUrl, pageTitle, recentActions
                                ));
                                aiCallCount++;
                                planHolder[0] = newPlan;
                                String newPlanText = formatPlanText(newPlan);
                                task.setPlan(newPlanText);
                                task.setSuccessCriteria(newPlan.successCriteria());
                                taskRepository.save(task);
                                publishEvent(TaskEvent.stepUpdate(taskId, "RUNNING",
                                    "Replanned: " + newPlan.steps().size() + " new steps", step, Map.of("plan", newPlanText)));
                                consecutiveFailures = 0;
                                step = -1; // will be incremented to 0 by the loop
                                recentActionFingerprints.clear();
                                continue;
                            } catch (Exception replanEx) {
                                log.warn("Task {} — replan failed: {}", taskId, replanEx.getMessage());
                            }
                        }
                        failTask(task, "Browser action failed " + maxRetries + " times (after " + replanCount + " replans): " + result.error());
                        return;
                    }
                    BrowserState state = browserWorkerClient.getState(taskId, sessionId);
                    if (state.currentUrl() != null) {
                        currentUrl = state.currentUrl();
                        pageText = state.pageText() != null ? state.pageText() : "";
                        pageTitle = state.pageTitle() != null ? state.pageTitle() : "";
                        String updatedHash = computeObservationHash(currentUrl, pageTitle, pageText, lastInteractiveElements);
                        if (!updatedHash.equals(lastObservationHash)) {
                            observationEpoch++;
                            lastObservationHash = updatedHash;
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

            publishEvent(TaskEvent.debug(taskId, "[STEP 4/5] SYNTHESIZE — " + visitedUrls.size() + " URLs, " + textSnippets.size() + " snippets, deliverable=" + task.getDeliverableType(), Map.of("phase", "SYNTHESIZE", "keyPool", getKeyPoolState())));
            String synthesized = aiProvider.synthesizeResult(
                new AiProvider.SynthesisRequest(
                    task.getUserRequest(),
                    task.getWorkflowType().name(),
                    task.getDeliverableType(),
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
            evidenceSummary.add("Captured " + screenshotCount + " step screenshots");
            evidenceSummary.add("Observation epochs: " + observationEpoch);
            if (!visitedUrls.isEmpty()) {
                evidenceSummary.add("Final URL: " + currentUrl);
            }
            // Add screenshot log for verification to cite specific evidence
            evidenceSummary.addAll(screenshotLog);

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
                .deliverableType(task.getDeliverableType())
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

    // ─── Pause / Resume state machine ─────────────────────────────────────────

    private static final int PAUSE_TIMEOUT_SECONDS = 30 * 60; // 30 minutes
    private static final int PAUSE_POLL_INTERVAL_MS = 2000;

    record PauseResult(
        boolean resumed,
        String failureReason,
        String currentUrl,
        String pageTitle,
        String pageText,
        String observationId,
        java.util.List<java.util.Map<String, Object>> interactiveElements
    ) {
        static PauseResult failed(String reason) {
            return new PauseResult(false, reason, null, null, null, null, null);
        }
        static PauseResult success(ActionResult unparkResult) {
            return new PauseResult(true, null,
                unparkResult.currentUrl(), unparkResult.pageTitle(),
                unparkResult.pageText(), unparkResult.observationId(),
                unparkResult.interactiveElements());
        }
    }

    /**
     * Pause the task for user interaction. Parks the browser session (keeps it alive),
     * transitions to WAITING_FOR_USER, emits a "waiting" SSE event, and polls the
     * approval record until resolved or the 30-minute timeout expires.
     *
     * On resume: unparks the session (gets fresh page state) and transitions back to RUNNING.
     * On timeout: fails the task with WAIT_TIMEOUT.
     */
    PauseResult pauseForUser(Task task, String sessionId, UUID approvalId,
                             String reason, String proposedAction, int currentStep,
                             Map<String, Object> parameters) {
        UUID taskId = task.getId();
        log.info("Task {} pausing for user at step {} — reason: {}", taskId, currentStep, reason);

        // Park the browser session so it stays alive during the wait
        browserWorkerClient.parkSession(taskId);

        // Persist the pause state
        task.setCurrentStep(currentStep);
        updateTaskStatus(task, TaskStatus.WAITING_FOR_USER, null);

        // Emit events
        publishEvent(TaskEvent.approvalRequired(taskId, approvalId, reason, proposedAction, parameters));
        publishEvent(TaskEvent.waiting(taskId, reason, Map.of(
            "approvalId", approvalId.toString(),
            "proposedAction", proposedAction != null ? proposedAction : "",
            "step", currentStep,
            "timeoutSeconds", PAUSE_TIMEOUT_SECONDS
        )));

        // Poll for resolution
        int waited = 0;
        while (waited < PAUSE_TIMEOUT_SECONDS * 1000) {
            if (isCancelled(taskId)) {
                return PauseResult.failed("Task cancelled while waiting for user");
            }

            try { Thread.sleep(PAUSE_POLL_INTERVAL_MS); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return PauseResult.failed("Interrupted while waiting for user");
            }
            waited += PAUSE_POLL_INTERVAL_MS;

            ApprovalRequest approval = approvalRepository.findById(approvalId).orElse(null);
            if (approval == null) {
                return PauseResult.failed("Approval request disappeared");
            }

            if ("APPROVED".equals(approval.getStatus())) {
                log.info("Task {} approval {} approved — resuming", taskId, approvalId);
                // Unpark the browser and get fresh state
                ActionResult unparkResult = browserWorkerClient.unparkSession(taskId);
                if (!unparkResult.success()) {
                    return PauseResult.failed("Browser session lost during wait: " + unparkResult.error());
                }
                updateTaskStatus(task, TaskStatus.RUNNING, null);
                publishEvent(TaskEvent.statusChange(taskId, "RUNNING", "Resumed after user approval"));
                return PauseResult.success(unparkResult);
            }

            if ("REJECTED".equals(approval.getStatus())) {
                return PauseResult.failed("Action rejected by user" +
                    (approval.getUserResponse() != null ? ": " + approval.getUserResponse() : ""));
            }

            if (approval.isExpired()) {
                approval.setStatus("EXPIRED");
                approvalRepository.save(approval);
                return PauseResult.failed("Approval expired while waiting for user response");
            }
        }

        // 30-minute timeout
        log.warn("Task {} WAIT_TIMEOUT after {}s", taskId, PAUSE_TIMEOUT_SECONDS);
        return PauseResult.failed("WAIT_TIMEOUT: no user response within " + (PAUSE_TIMEOUT_SECONDS / 60) + " minutes");
    }

    // ─── Credential gate (PIECE 1) ──────────────────────────────────────────────

    private static final java.util.regex.Pattern LOGIN_PATTERN = java.util.regex.Pattern.compile(
        "(?i)(log\\s*in|sign\\s*in|authenticate|enter.*password|enter.*credentials|provide.*username)"
    );

    record CredentialGateResult(
        boolean needsPause,
        boolean resolved,
        Map<String, String> credentials // domain -> "username:password" (only for this run)
    ) {
        static CredentialGateResult noop() {
            return new CredentialGateResult(false, true, Map.of());
        }
    }

    /**
     * Scan plan steps for login/sign-in indicators. If found, try to extract credentials
     * from the task instruction. If not present, pause to ask the user.
     *
     * SECURITY: credentials from the instruction are used for THIS RUN ONLY and never persisted to the vault.
     */
    CredentialGateResult checkCredentialGate(Task task, List<String> planSteps, String sessionId) {
        UUID taskId = task.getId();
        String userRequest = task.getUserRequest();

        // 1. Do any plan steps involve login?
        boolean hasLoginStep = planSteps.stream().anyMatch(s -> LOGIN_PATTERN.matcher(s).find());
        if (!hasLoginStep) {
            // Also check if the user request itself mentions login
            if (!LOGIN_PATTERN.matcher(userRequest).find()) {
                return CredentialGateResult.noop();
            }
        }

        log.info("Task {} — credential gate triggered: login step detected in plan", taskId);

        // 2. Try to extract a target domain from the user request
        String targetDomain = extractDomain(userRequest);

        // 3. Check the vault for stored credentials
        if (targetDomain != null && credentialVaultService.hasDomain(targetDomain)) {
            log.info("Task {} — vault has credentials for domain={}, skipping approval", taskId, targetDomain);
            publishEvent(TaskEvent.debug(taskId, "Using vault credentials for " + targetDomain,
                Map.of("domain", targetDomain)));
            // Resolve vault credentials — the actual password stays in the vault service,
            // we just pass a reference that the worker will resolve at fill time
            var resolved = credentialVaultService.resolve(targetDomain);
            if (resolved.isPresent()) {
                Map<String, String> creds = new HashMap<>();
                creds.put(targetDomain, resolved.get().username() + "\n" + resolved.get().password());
                return new CredentialGateResult(true, true, creds);
            }
        }

        // 4. Check if credentials are embedded in the user request
        Map<String, String> extractedCreds = extractCredentialsFromInstruction(userRequest);
        if (!extractedCreds.isEmpty()) {
            log.info("Task {} — credentials found in instruction for domain(s): {}", taskId, extractedCreds.keySet());
            publishEvent(TaskEvent.debug(taskId, "Credentials found in instruction — using for this run only",
                Map.of("domains", extractedCreds.keySet().toString())));
            return new CredentialGateResult(true, true, extractedCreds);
        }

        // 5. No credentials in instruction or vault — pause and ask the user
        log.info("Task {} — no credentials in instruction, pausing for user", taskId);

        String domain = targetDomain != null ? targetDomain : "unknown site";
        ApprovalRequest approval = ApprovalRequest.builder()
            .taskId(taskId)
            .prompt("Login required for " + domain)
            .proposedAction("CREDENTIAL_REQUEST")
            .parameters(Map.of(
                "type", "credential_request",
                "domain", domain,
                "fields", List.of("username", "password"),
                "saveToVault", false
            ))
            .status("PENDING")
            .build();
        approval = approvalRepository.save(approval);

        // Use the pause/resume machine (but sessionId may be null if we haven't launched browser yet)
        // We need to init the browser first for the pause to work, or handle pre-browser pause
        if (sessionId == null) {
            // We haven't launched the browser yet — do a lightweight pause without parking
            log.info("[GATE] login detected — waiting for credentials (domain={})", domain);
            updateTaskStatus(task, TaskStatus.WAITING_FOR_USER, null);
            publishEvent(TaskEvent.approvalRequired(taskId, approval.getId(),
                "Login required for " + domain, "CREDENTIAL_REQUEST", approval.getParameters()));
            publishEvent(TaskEvent.waiting(taskId, "Login required for " + domain, Map.of(
                "approvalId", approval.getId().toString(),
                "type", "credential_request",
                "domain", domain,
                "timeoutSeconds", PAUSE_TIMEOUT_SECONDS
            )));

            // Poll for resolution
            int waited = 0;
            while (waited < PAUSE_TIMEOUT_SECONDS * 1000) {
                if (isCancelled(taskId)) {
                    failTask(task, "Task cancelled while waiting for credentials");
                    return new CredentialGateResult(true, false, Map.of());
                }
                try { Thread.sleep(PAUSE_POLL_INTERVAL_MS); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    failTask(task, "Interrupted while waiting for credentials");
                    return new CredentialGateResult(true, false, Map.of());
                }
                waited += PAUSE_POLL_INTERVAL_MS;

                ApprovalRequest resolved = approvalRepository.findById(approval.getId()).orElse(null);
                if (resolved == null) {
                    failTask(task, "Credential request disappeared");
                    return new CredentialGateResult(true, false, Map.of());
                }
                if ("APPROVED".equals(resolved.getStatus())) {
                    updateTaskStatus(task, TaskStatus.RUNNING, null);
                    publishEvent(TaskEvent.statusChange(taskId, "RUNNING", "Credentials received"));
                    // Extract credentials from the approval response
                    Map<String, String> creds = parseCredentialResponse(resolved, domain);
                    return new CredentialGateResult(true, true, creds);
                }
                if ("REJECTED".equals(resolved.getStatus())) {
                    failTask(task, "User declined to provide credentials");
                    return new CredentialGateResult(true, false, Map.of());
                }
                if (resolved.isExpired()) {
                    resolved.setStatus("EXPIRED");
                    approvalRepository.save(resolved);
                    failTask(task, "Credential request expired");
                    return new CredentialGateResult(true, false, Map.of());
                }
            }
            failTask(task, "WAIT_TIMEOUT: no credentials provided within " + (PAUSE_TIMEOUT_SECONDS / 60) + " minutes");
            return new CredentialGateResult(true, false, Map.of());
        }

        // Browser already running — use full pause/resume
        PauseResult pauseResult = pauseForUser(task, sessionId, approval.getId(),
            "Login required for " + domain, "CREDENTIAL_REQUEST", task.getCurrentStep(), approval.getParameters());
        if (!pauseResult.resumed) {
            failTask(task, pauseResult.failureReason());
            return new CredentialGateResult(true, false, Map.of());
        }
        ApprovalRequest resolved = approvalRepository.findById(approval.getId()).orElse(null);
        Map<String, String> creds = resolved != null ? parseCredentialResponse(resolved, domain) : Map.of();
        return new CredentialGateResult(true, true, creds);
    }

    private String extractDomain(String text) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
            "(?:https?://)?([a-zA-Z0-9][-a-zA-Z0-9]*\\.[a-zA-Z]{2,}(?:\\.[a-zA-Z]{2,})?)"
        ).matcher(text);
        return m.find() ? m.group(1).toLowerCase() : null;
    }

    /**
     * Extract credentials from the user's instruction text.
     * Looks for patterns like "username: X, password: Y" or "login with X / Y".
     * Returns domain -> "username\npassword" mapping.
     */
    private Map<String, String> extractCredentialsFromInstruction(String text) {
        Map<String, String> result = new HashMap<>();
        String domain = extractDomain(text);
        if (domain == null) domain = "default";

        // Pattern: username: X password: Y (or user/pass variants)
        java.util.regex.Matcher userMatcher = java.util.regex.Pattern.compile(
            "(?i)(?:user(?:name)?|login|email)[:\\s]+[\"']?([^\"',\\s]+)[\"']?"
        ).matcher(text);
        java.util.regex.Matcher passMatcher = java.util.regex.Pattern.compile(
            "(?i)(?:pass(?:word)?|secret|pin)[:\\s]+[\"']?([^\"',\\s]+)[\"']?"
        ).matcher(text);

        if (userMatcher.find() && passMatcher.find()) {
            result.put(domain, userMatcher.group(1) + "\n" + passMatcher.group(1));
        }
        return result;
    }

    private Map<String, String> parseCredentialResponse(ApprovalRequest approval, String domain) {
        Map<String, String> creds = new HashMap<>();
        String response = approval.getUserResponse();

        if (response != null && !response.isBlank()) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> parsed = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(response, Map.class);
                String username = (String) parsed.getOrDefault("username", "");
                String password = (String) parsed.getOrDefault("password", "");
                boolean saveToVault = Boolean.TRUE.equals(parsed.get("saveToVault"));

                if (!username.isBlank() && !password.isBlank()) {
                    creds.put(domain, username + "\n" + password);

                    if (saveToVault) {
                        try {
                            credentialVaultService.upsert(domain, username, password);
                            log.info("Credentials saved to vault for domain={}", domain);
                        } catch (Exception e) {
                            log.warn("Failed to save credentials to vault for domain={}: {}", domain, e.getMessage());
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("Could not parse credential response: {}", e.getMessage());
            }
        }
        return creds;
    }

    private void saveStepScreenshot(UUID taskId, int stepIndex, String actionType,
                                    String observationId, boolean success, String label,
                                    String screenshotBase64, String sourceUrl) {
        try {
            String content = screenshotBase64;
            if (content != null && content.length() > 500_000) {
                content = content.substring(0, 500_000);
            }
            String metadata = String.format(
                "{\"stepIndex\":%d,\"action\":\"%s\",\"observationId\":\"%s\",\"success\":%s,\"timestamp\":\"%s\"}",
                stepIndex, actionType,
                observationId != null ? observationId : "",
                success, Instant.now().toString()
            );
            EvidenceArtifact evidence = EvidenceArtifact.builder()
                .taskId(taskId)
                .artifactType("STEP_SCREENSHOT")
                .label(label)
                .content(content)
                .sourceUrl(sourceUrl)
                .build();
            evidenceRepository.save(evidence);
        } catch (Exception e) {
            log.warn("Could not save step screenshot for task {} step {}: {}", taskId, stepIndex, e.getMessage());
        }
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
        return computeObservationHash(url, title, text, null);
    }

    private String computeObservationHash(String url, String title, String text, List<Map<String, Object>> elements) {
        StringBuilder sb = new StringBuilder();
        sb.append(url != null ? url : "").append("|")
          .append(title != null ? title : "").append("|")
          .append(text != null ? text.substring(0, Math.min(500, text.length())) : "");
        if (elements != null) {
            for (Map<String, Object> el : elements) {
                Object v = el.get("value");
                if (v != null && !v.toString().isBlank()) {
                    sb.append("|").append(el.get("index")).append("=").append(v);
                }
            }
        }
        return Integer.toHexString(sb.toString().hashCode());
    }

    private static String formatPlanText(AiProvider.TaskPlan plan) {
        List<String> steps = plan.steps();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < steps.size(); i++) {
            if (i > 0) sb.append("\n");
            sb.append(i + 1).append(". ").append(steps.get(i));
        }
        return sb.toString();
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

    // ─── CAPTCHA / 2FA challenge handoff ────────────────────────────────────────

    record ChallengeHandleResult(
        boolean resumed,
        String failureReason,
        String currentUrl,
        String pageTitle,
        String pageText,
        String observationId,
        List<Map<String, Object>> interactiveElements
    ) {
        static ChallengeHandleResult failed(String reason) {
            return new ChallengeHandleResult(false, reason, null, null, null, null, null);
        }
    }

    ChallengeHandleResult handleChallenges(Task task, String sessionId, int currentStep,
                                           ActionResult actionResult) {
        UUID taskId = task.getId();
        List<Map<String, Object>> challenges = actionResult.challenges();
        String firstType = (String) challenges.get(0).getOrDefault("type", "unknown");
        String provider = (String) challenges.get(0).getOrDefault("provider", "");
        String inputName = (String) challenges.get(0).getOrDefault("inputName", null);

        boolean isOtp = "otp".equals(firstType);
        String approvalType = isOtp ? "otp_request" : "captcha_request";
        String prompt = isOtp
            ? "2FA/OTP verification code required"
            : "CAPTCHA detected" + (provider != null && !provider.isEmpty() ? " (" + provider + ")" : "")
                + " — please solve it in the browser";

        log.info("Task {} — challenge detected: type={} provider={}", taskId, firstType, provider);

        Map<String, Object> params = new HashMap<>();
        params.put("type", approvalType);
        params.put("challengeType", firstType);
        if (provider != null) params.put("provider", provider);
        if (inputName != null) params.put("inputName", inputName);

        ApprovalRequest approval = ApprovalRequest.builder()
            .taskId(taskId)
            .prompt(prompt)
            .proposedAction(approvalType.toUpperCase())
            .parameters(params)
            .status("PENDING")
            .build();
        approval = approvalRepository.save(approval);

        PauseResult pauseResult = pauseForUser(task, sessionId, approval.getId(),
            prompt, approvalType.toUpperCase(), currentStep, params);

        if (!pauseResult.resumed) {
            return ChallengeHandleResult.failed(pauseResult.failureReason());
        }

        // For OTP: fill the code directly via the worker (bypasses the model)
        if (isOtp) {
            ApprovalRequest resolved = approvalRepository.findById(approval.getId()).orElse(null);
            String otpCode = parseOtpCode(resolved);
            if (otpCode != null && !otpCode.isBlank()) {
                ActionResult otpResult = browserWorkerClient.fillOtp(taskId, otpCode.trim(), inputName);
                if (!otpResult.success()) {
                    log.warn("Task {} — OTP fill failed: {}", taskId, otpResult.error());
                }
                return new ChallengeHandleResult(true, null,
                    otpResult.currentUrl(), otpResult.pageTitle(),
                    pauseResult.pageText(), otpResult.observationId(),
                    pauseResult.interactiveElements());
            }
        }

        // For CAPTCHA: user solved it in the browser, just resume with unparked state
        return new ChallengeHandleResult(true, null,
            pauseResult.currentUrl(), pauseResult.pageTitle(),
            pauseResult.pageText(), pauseResult.observationId(),
            pauseResult.interactiveElements());
    }

    private String parseOtpCode(ApprovalRequest approval) {
        if (approval == null) return null;
        String response = approval.getUserResponse();
        if (response == null || response.isBlank()) return null;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(response, Map.class);
            String code = (String) parsed.get("otpCode");
            if (code != null && !code.isBlank()) return code;
        } catch (Exception e) {
            // Not JSON — treat the raw response as the code
        }
        return response.trim();
    }

    // ─── Credential placeholder system ─────────────────────────────────────────
    // The model never sees actual passwords. Instead, it gets placeholders like
    // __cred_username:saucedemo.com__ and __cred_password:saucedemo.com__.
    // These are resolved to actual values here, just before dispatching to the worker.

    private static final java.util.regex.Pattern CRED_PLACEHOLDER = java.util.regex.Pattern.compile(
        "__cred_(username|password):([^_]+)__"
    );

    private String buildCredentialHints(Map<String, String> taskCredentials) {
        if (taskCredentials.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        sb.append("\nCREDENTIALS AVAILABLE (use these exact placeholder values in FILL actions):\n");
        for (Map.Entry<String, String> entry : taskCredentials.entrySet()) {
            String domain = entry.getKey();
            String[] parts = entry.getValue().split("\n", 2);
            String username = parts.length > 0 ? parts[0] : "";
            sb.append("  Domain: ").append(domain).append("\n");
            sb.append("    Username field → FILL value: \"").append(username).append("\"\n");
            sb.append("    Password field → FILL value: \"__cred_password:").append(domain).append("__\"\n");
        }
        sb.append("IMPORTANT: Use the exact placeholder string for password fields. Never guess or invent passwords.\n");
        return sb.toString();
    }

    private String resolveCredentialPlaceholder(String value, Map<String, String> taskCredentials) {
        java.util.regex.Matcher m = CRED_PLACEHOLDER.matcher(value);
        if (!m.matches()) return value;
        String field = m.group(1);
        String domain = m.group(2);
        String creds = taskCredentials.get(domain);
        if (creds == null) {
            log.warn("No credentials for domain={} to resolve placeholder", domain);
            return value;
        }
        String[] parts = creds.split("\n", 2);
        if ("username".equals(field)) {
            return parts.length > 0 ? parts[0] : value;
        } else if ("password".equals(field)) {
            return parts.length > 1 ? parts[1] : value;
        }
        return value;
    }

    private String getKeyPoolState() {
        if (aiProvider instanceof GeminiAiProvider gp) {
            return gp.getKeyPool().getKeyStates().toString();
        }
        return "N/A";
    }
}
