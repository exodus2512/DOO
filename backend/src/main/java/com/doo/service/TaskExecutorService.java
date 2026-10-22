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
import java.util.Objects;
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
    private final RoutePreferenceService routePreferenceService;

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

    // Per-domain cooldown tracker after bot-blocks or rate-limits
    private static final Map<String, Instant> DOMAIN_COOLDOWNS = new ConcurrentHashMap<>();

    public static boolean isDomainInCooldown(String domain) {
        if (domain == null) return false;
        Instant cooldownUntil = DOMAIN_COOLDOWNS.get(domain.toLowerCase());
        return cooldownUntil != null && Instant.now().isBefore(cooldownUntil);
    }

    public static void setDomainCooldown(String domain, Duration duration) {
        if (domain != null) {
            DOMAIN_COOLDOWNS.put(domain.toLowerCase(), Instant.now().plus(duration));
        }
    }

    public static Duration getDomainCooldownRemaining(String domain) {
        if (domain == null) return Duration.ZERO;
        Instant cooldownUntil = DOMAIN_COOLDOWNS.get(domain.toLowerCase());
        if (cooldownUntil == null || Instant.now().isAfter(cooldownUntil)) return Duration.ZERO;
        return Duration.between(Instant.now(), cooldownUntil);
    }

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
        Set<String> blockedDomains = new LinkedHashSet<>();
        Map<String, String> officialAlternatives = new LinkedHashMap<>();

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
            // User-provided values from ASK_USER, keyed by normalized field label/autocomplete.
            // The AI prompt only sees "__user:key__" placeholders; actual values resolve at FILL time.
            Map<String, String> knownValues = new HashMap<>();
            if (wfType.requiresBrowser()) {
                CredentialGateResult credResult = checkCredentialGate(task, planHolder[0].steps(), sessionId);
                if (credResult.needsPause && !credResult.resolved) {
                    return;
                }
                taskCredentials.putAll(credResult.credentials);
                credentialHints = buildCredentialHints(taskCredentials);
            }

            // ── ROUTE_PREFERENCE Step & Domain Cooldown ──
            String targetDomainForSession = extractDomain(task.getUserRequest());
            if (targetDomainForSession != null && isDomainInCooldown(targetDomainForSession)) {
                Duration remaining = getDomainCooldownRemaining(targetDomainForSession);
                String cooldownMsg = String.format("Domain %s is currently in polite cooldown for %ds after an access block. Halting to avoid block escalation.",
                    targetDomainForSession, remaining.toSeconds());
                log.warn("Task {} halted: {}", taskId, cooldownMsg);
                failTask(task, cooldownMsg);
                return;
            }

            String initialUrl = extractTargetUrl(task.getUserRequest());
            if (initialUrl != null && routePreferenceService != null) {
                RoutePreferenceService.RoutePreferenceResult pref = routePreferenceService.evaluate(initialUrl, task.getUserRequest());
                if (!pref.isCanProceed()) {
                    log.warn("Task {} disallowed by robots.txt: {}", taskId, pref.getStopReason());
                    failTask(task, pref.getStopReason());
                    return;
                }
                if (pref.getRouteType() != RoutePreferenceService.RouteType.BROWSER_ALLOWED) {
                    if (pref.getOfficialRouteUrl() != null && targetDomainForSession != null) {
                        officialAlternatives.put(targetDomainForSession, pref.getOfficialRouteUrl() + " (" + pref.getRouteType() + ")");
                    }
                    publishEvent(TaskEvent.stepUpdate(taskId, "RUNNING",
                        "Official route identified: " + pref.getDescription(), 0,
                        Map.of("routeType", pref.getRouteType().name(), "url", pref.getOfficialRouteUrl() != null ? pref.getOfficialRouteUrl() : "")));
                    recentActions.add("ROUTE_PREFERENCE: identified " + pref.getRouteType() + " route for " + targetDomainForSession);
                }
            }

            // Step 2: Initialize browser session
            publishEvent(TaskEvent.stepUpdate(taskId, "RUNNING", "Launching browser...", 0, Map.of()));
            // Pass target domain so the worker can load saved storage state
            SessionResult session = browserWorkerClient.initSession(taskId, targetDomainForSession);
            if (session == null) {
                session = browserWorkerClient.initSession(taskId);
            }

            if (session == null || !session.success()) {
                String err = (session != null && session.error() != null) ? session.error() : "connection failed";
                failTask(task, "Could not launch browser: " + err);
                return;
            }
            sessionId = session.sessionId();
            publishEvent(TaskEvent.stepUpdate(taskId, "RUNNING", "Browser ready", 0, Map.of()));

            // Step 3: Execution loop
            String currentUrl = "";
            String pageTitle = "";
            String pageText = "";
            String accessibilityTree = "";
            String currentPageState = "NORMAL";
            String lastScreenshot = null;
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
            // Track element indices successfully FILLed in the current observation cycle.
            // Cleared whenever the observation ID changes. Prevents re-fill loops on
            // password fields (value="***") and other fields whose DOM value is masked.
            Set<Integer> filledInCurrentObservation = new HashSet<>();
            String filledObservationId = null;
            // Tracks the last CLICK element_index + page error state, to catch repeated
            // failed clicks (e.g. clicking Login 7 times with "Password is required").
            Integer lastClickElementIndex = null;
            boolean lastClickHadError = false;
            // Approval dedup cache: keyed by (proposedAction + sorted field names).
            // Stores the JSON response string so repeated asks reuse the prior answer.
            Map<String, String> approvalAnswerCache = new HashMap<>();
            // Domains where auth has been handled this session — prevents repeated auth cards.
            Set<String> authHandledDomains = new HashSet<>();

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

                // ── Auth surface gate ──────────────────────────────────────────────────────
                // After observing a new page, detect auth surfaces in the DOM and show a card
                // matching what is actually available. This replaces plan-text credential scanning.
                if (lastInteractiveElements != null && currentUrl != null && !currentUrl.isBlank()) {
                    String authDomain = extractDomain(currentUrl);
                    if (authDomain != null && !authHandledDomains.contains(authDomain)
                            && !taskCredentials.containsKey(authDomain)) {
                        AuthSurface authSurface = detectAuthSurface(lastInteractiveElements, pageText, currentUrl);
                        if (authSurface.isAuthPage()) {
                            log.info("Task {} step {} | AUTH GATE: auth surface on {} (password={}, oauth={}, emailOnly={})",
                                taskId, step, authDomain, authSurface.hasPasswordForm(),
                                authSurface.oauthProviders(), authSurface.hasEmailOnlyForm());

                            List<Map<String, Object>> authOptions = new ArrayList<>();
                            authOptions.add(Map.of("value", "handoff", "label", "Sign in in the browser window",
                                "description", "I'll complete the login myself in the browser."));
                            if (authSurface.hasPasswordForm()) {
                                authOptions.add(Map.of("value", "password", "label", "Enter username + password",
                                    "description", "Provide credentials and the agent will fill them."));
                            }
                            if (authSurface.hasEmailOnlyForm()) {
                                authOptions.add(Map.of("value", "email_link", "label", "Enter email address",
                                    "description", "Receive a magic link or verification code."));
                            }
                            if (authSurface.hasPhoneOtp()) {
                                authOptions.add(Map.of("value", "phone", "label", "Enter phone number",
                                    "description", "Receive a verification code via SMS."));
                            }
                            if (!authSurface.oauthProviders().isEmpty()) {
                                authOptions.add(Map.of("value", "oauth_note",
                                    "label", "OAuth: " + String.join(", ", authSurface.oauthProviders()),
                                    "description", "Choose 'Sign in in the browser' to use a social login."));
                            }
                            String authMethods = !authSurface.oauthProviders().isEmpty()
                                ? String.join(", ", authSurface.oauthProviders())
                                    + (authSurface.hasPasswordForm() ? ", Email+Password" : "")
                                    + (authSurface.hasEmailOnlyForm() ? ", Email link" : "")
                                : (authSurface.hasPasswordForm() ? "Email+Password"
                                    : (authSurface.hasEmailOnlyForm() ? "Email link" : "Phone/OTP"));
                            String authReason = "Login required. Available sign-in methods: " + authMethods;
                            Map<String, Object> authParams = new HashMap<>();
                            authParams.put("type", "auth_gate");
                            authParams.put("authMethods", authMethods);
                            authParams.put("options", authOptions);
                            authParams.put("hasPasswordForm", authSurface.hasPasswordForm());
                            authParams.put("oauthProviders", authSurface.oauthProviders());
                            authParams.put("domain", authDomain);
                            authParams.put("reason", authReason);

                            String authDedupKey = "AUTH_GATE:" + authDomain;
                            String cachedAuthResponse = approvalAnswerCache.get(authDedupKey);
                            if (cachedAuthResponse != null) {
                                log.info("Task {} — AUTH GATE: reusing cached auth response for {}", taskId, authDomain);
                                handleAuthGateResponse(cachedAuthResponse, authDomain, taskCredentials,
                                    knownValues, authHandledDomains, taskId);
                                credentialHints = buildFullHints(taskCredentials, knownValues);
                                continue;
                            }

                            // Bring window to front for OAuth/handoff scenarios
                            if (!authSurface.hasPasswordForm() || !authSurface.oauthProviders().isEmpty()) {
                                browserWorkerClient.bringToFront(taskId);
                            }

                            ApprovalRequest authApproval = findOrCreateApproval(taskId, "AUTH_GATE",
                                authReason, authParams);
                            PauseResult authPause = pauseForUser(task, sessionId, authApproval.getId(),
                                authReason, "AUTH_GATE", step, authParams);
                            if (!authPause.resumed) { failTask(task, authPause.failureReason()); return; }

                            ApprovalRequest authResolved = approvalRepository.findById(authApproval.getId()).orElse(null);
                            if (authResolved != null && authResolved.getUserResponse() != null) {
                                handleAuthGateResponse(authResolved.getUserResponse(), authDomain,
                                    taskCredentials, knownValues, authHandledDomains, taskId);
                                approvalAnswerCache.put(authDedupKey, authResolved.getUserResponse());
                                // Verify login succeeded before saving storage state
                                boolean loginVerified = false;
                                for (int loginAttempt = 0; loginAttempt < 3; loginAttempt++) {
                                    List<Map<String, Object>> verifyEls = authPause.interactiveElements() != null
                                        ? authPause.interactiveElements() : lastInteractiveElements;
                                    String verifyText = authPause.pageText() != null ? authPause.pageText() : pageText;
                                    String verifyUrl = authPause.currentUrl() != null ? authPause.currentUrl() : currentUrl;
                                    if (verifyLoggedIn(verifyEls, verifyText, verifyUrl, authSurface)) {
                                        loginVerified = true;
                                        log.info("Task {} — AUTH GATE: login verified for {} (attempt {})", taskId, authDomain, loginAttempt + 1);
                                        String domainForSave = extractDomain(verifyUrl);
                                        if (domainForSave != null) browserWorkerClient.saveStorageState(taskId, domainForSave);
                                        break;
                                    }
                                    if (loginAttempt < 2) {
                                        try { Thread.sleep(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                                    }
                                }
                                if (!loginVerified) {
                                    log.warn("Task {} — AUTH GATE: login not verified after 3 attempts for {}", taskId, authDomain);
                                    authHandledDomains.remove(authDomain);
                                    approvalAnswerCache.remove(authDedupKey);
                                    recentActions.add("AUTH_GATE: login verification failed — will retry");
                                }
                            } else {
                                authHandledDomains.add(authDomain);
                            }
                            credentialHints = buildFullHints(taskCredentials, knownValues);
                            if (authPause.observationId() != null) {
                                lastObservationId = authPause.observationId();
                                lastInteractiveElements = authPause.interactiveElements();
                            }
                            if (authPause.currentUrl() != null) currentUrl = authPause.currentUrl();
                            if (authPause.pageTitle() != null) pageTitle = authPause.pageTitle();
                            if (authPause.pageText() != null) {
                                pageText = authPause.pageText();
                                lastObservationHash = computeObservationHash(currentUrl, pageTitle, pageText, lastInteractiveElements);
                                observationEpoch++;
                            }
                            recentActions.add("AUTH_GATE: user handled auth for " + authDomain);
                            continue;
                        }
                    }
                }

                // ── Step B: Deterministic form gate ─────────────────────────────
                // Before consulting the AI, scan the current page for empty required/
                // personal-data fields whose values are not yet in knownValues.
                // If any are found, skip the model and fire ASK_USER directly.
                // Skip on login pages (pages with a visible password field).
                boolean hasPasswordField = lastInteractiveElements != null && lastInteractiveElements.stream()
                    .anyMatch(e -> "password".equals(String.valueOf(e.getOrDefault("type", ""))));
                if (lastInteractiveElements != null && !hasPasswordField) {
                    List<Map<String, Object>> missingFields = detectMissingFields(
                        lastInteractiveElements, knownValues, task.getUserRequest());
                    if (!missingFields.isEmpty()) {
                        log.info("Task {} step {} | FORM GATE: {} missing fields detected, triggering ASK_USER",
                            taskId, step, missingFields.size());
                        publishEvent(TaskEvent.stepUpdate(taskId, "RUNNING",
                            "Form detected with " + missingFields.size() + " fields needing input — asking user",
                            step, Map.of()));

                        String askReason = "This form requires personal information. Please provide the details below.";
                        Map<String, Object> approvalParams = new HashMap<>();
                        approvalParams.put("type", "form_input_request");
                        approvalParams.put("fields", missingFields);
                        approvalParams.put("reason", askReason);

                        // Dedup: reuse cached answer if this exact field set was answered before
                        String gateDedupKey = approvalDedupKey("ASK_USER", approvalParams);
                        String cachedGateResponse = approvalAnswerCache.get(gateDedupKey);
                        if (cachedGateResponse != null) {
                            log.info("Task {} step {} | FORM GATE: reusing cached answer (dedup key={})", taskId, step, gateDedupKey);
                            storeUserValues(cachedGateResponse, knownValues, taskId);
                            credentialHints = buildFullHints(taskCredentials, knownValues);
                            recentActions.add("FORM_GATE → CACHED: reused prior answer for " + missingFields.size() + " fields");
                            continue;
                        }

                        ApprovalRequest gateApproval = ApprovalRequest.builder()
                            .taskId(taskId).prompt(askReason).proposedAction("ASK_USER")
                            .parameters(approvalParams).status("PENDING").build();
                        gateApproval = approvalRepository.save(gateApproval);

                        PauseResult gatePause = pauseForUser(task, sessionId, gateApproval.getId(),
                            askReason, "ASK_USER", step, approvalParams);
                        if (!gatePause.resumed) { failTask(task, gatePause.failureReason()); return; }

                        ApprovalRequest gateResolved = approvalRepository.findById(gateApproval.getId()).orElse(null);
                        if (gateResolved != null && gateResolved.getUserResponse() != null) {
                            storeUserValues(gateResolved.getUserResponse(), knownValues, taskId);
                            approvalAnswerCache.put(gateDedupKey, gateResolved.getUserResponse());
                        }
                        // Rebuild credential hints with user-value placeholders
                        credentialHints = buildFullHints(taskCredentials, knownValues);

                        if (gatePause.observationId != null) {
                            lastObservationId = gatePause.observationId;
                            lastInteractiveElements = gatePause.interactiveElements;
                        }
                        if (gatePause.currentUrl != null) currentUrl = gatePause.currentUrl;
                        if (gatePause.pageTitle != null) pageTitle = gatePause.pageTitle;
                        if (gatePause.pageText != null) {
                            pageText = gatePause.pageText;
                            lastObservationHash = computeObservationHash(currentUrl, pageTitle, pageText, lastInteractiveElements);
                            observationEpoch++;
                        }
                        recentActions.add("FORM_GATE → ASK_USER: user provided " + missingFields.size() + " fields");
                        continue;
                    }
                }

                // ── Bot block check before model call and before executing any action ──
                if ("BOT_BLOCK".equalsIgnoreCase(currentPageState)) {
                    log.warn("Task {} step {} | BOT_BLOCK before model call at {}", taskId, step, truncate(currentUrl, 80));
                    String blockDomain = extractDomain(currentUrl);
                    if (blockDomain != null) {
                        blockedDomains.add(blockDomain);
                        setDomainCooldown(blockDomain, Duration.ofMinutes(15));
                    }
                    if (lastScreenshot != null) {
                        saveEvidence(taskId, "STEP_SCREENSHOT",
                            "Bot block at " + truncate(currentUrl, 60), lastScreenshot, currentUrl);
                    }
                    Map<String, Object> bbParams = new HashMap<>();
                    bbParams.put("type", "bot_block");
                    bbParams.put("url", currentUrl);
                    bbParams.put("reason", "The site returned an access-denial page.");
                    bbParams.put("options", List.of(
                        "open_live_view: Open the live browser view",
                        "skip: Skip this source and try another",
                        "stop: Stop the task"));
                    String bbReason = "Bot block detected at " + truncate(currentUrl, 60);
                    ApprovalRequest bbApproval = findOrCreateApproval(taskId, "BOT_BLOCK", bbReason, bbParams);
                    PauseResult bbPause = pauseForUser(task, sessionId, bbApproval.getId(),
                        bbReason, "BOT_BLOCK", step, bbParams);
                    if (!bbPause.resumed) { failTask(task, bbPause.failureReason()); return; }
                    if (bbPause.observationId != null) {
                        lastObservationId = bbPause.observationId;
                        lastInteractiveElements = bbPause.interactiveElements;
                    }
                    if (bbPause.currentUrl != null) currentUrl = bbPause.currentUrl;
                    if (bbPause.pageTitle != null) pageTitle = bbPause.pageTitle;
                    if (bbPause.pageText != null) {
                        pageText = bbPause.pageText;
                        lastObservationHash = computeObservationHash(currentUrl, pageTitle, pageText, lastInteractiveElements);
                        observationEpoch++;
                    }
                    if (bbPause.pageState() != null) {
                        currentPageState = bbPause.pageState();
                    }
                    ApprovalRequest bbResolved = approvalRepository.findById(bbApproval.getId()).orElse(null);
                    String bbChoice = bbResolved != null && bbResolved.getUserResponse() != null
                        ? bbResolved.getUserResponse().toLowerCase() : "stop";
                    if (bbChoice.contains("stop")) {
                        failTask(task, "BOT_BLOCK: user chose to stop at " + truncate(currentUrl, 60));
                        return;
                    }
                    recentActions.add("BOT_BLOCK: user chose " + bbChoice + " at " + truncate(currentUrl, 50));
                    continue;
                }

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

                // ── page_state routing & bot block guard ──────────────────────────
                boolean isBotBlockPage = "BOT_BLOCK".equalsIgnoreCase(currentPageState)
                    || (decision.pageState() != null && "BOT_BLOCK".equalsIgnoreCase(decision.pageState()));
                if (isBotBlockPage) {
                    String actionType = decision.actionType() != null ? decision.actionType().toUpperCase() : "";
                    if (!"ASK_USER".equals(actionType) && !"DONE".equals(actionType)) {
                        log.warn("Task {} step {} | BOT_BLOCK rejection: cannot execute {} on blocked page", taskId, step, actionType);
                        String blockDomain = extractDomain(currentUrl);
                        if (blockDomain != null) {
                            blockedDomains.add(blockDomain);
                            setDomainCooldown(blockDomain, Duration.ofMinutes(15));
                        }
                        failTask(task, "site blocked automation (Cloudflare)");
                        return;
                    }
                }

                if (decision.pageState() != null) {
                    String ps = decision.pageState().toUpperCase();
                    log.debug("Task {} step {} | page_state={} why={}", taskId, step, ps,
                        decision.why() != null ? decision.why() : "");
                    if ("LOGIN".equals(ps)) {
                        // Auth gate runs at loop-top; skip this action and let it fire
                        recentActions.add("PAGE_STATE=LOGIN: deferring to auth gate");
                        continue;
                    }
                }

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

                    // Dedup: reuse cached answer if this exact field set was answered before
                    String askDedupKey = approvalDedupKey("ASK_USER", approvalParams);
                    String cachedAskResponse = approvalAnswerCache.get(askDedupKey);
                    if (cachedAskResponse != null) {
                        log.info("Task {} step {} | ASK_USER dedup: reusing cached answer (key={})", taskId, step, askDedupKey);
                        storeUserValues(cachedAskResponse, knownValues, taskId);
                        credentialHints = buildFullHints(taskCredentials, knownValues);
                        recentActions.add("ASK_USER: reused cached answer for " + fieldsList.size() + " fields");
                        continue;
                    }

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

                    // Parse user-provided field values into knownValues (Step C)
                    ApprovalRequest resolved = approvalRepository.findById(approval.getId()).orElse(null);
                    if (resolved != null && resolved.getUserResponse() != null) {
                        storeUserValues(resolved.getUserResponse(), knownValues, taskId);
                        credentialHints = buildFullHints(taskCredentials, knownValues);
                        approvalAnswerCache.put(askDedupKey, resolved.getUserResponse());
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

                // ── Repeated-failed-CLICK guard ───────────────────────────────────
                // If the model clicks the same element again after it produced a page error,
                // block it and force a replan. Prevents "click Login 7 times" loops.
                if ("CLICK".equals(decision.actionType()) && decision.args() != null) {
                    Object eidx = decision.args().get("element_index");
                    if (eidx != null) {
                        int idx = eidx instanceof Number n ? n.intValue() : Integer.parseInt(eidx.toString());
                        if (lastClickHadError && Objects.equals(idx, lastClickElementIndex)) {
                            log.warn("Task {} step {} | CLICK element {} blocked — last click on this element produced an error page",
                                taskId, step, idx);
                            String errSnippet = extractPageErrorText(pageText);
                            recentActions.add("CLICK_BLOCKED: retrying element " + idx + " after error: " + errSnippet);
                            // Force consecutive-failure path to trigger replan
                            lastClickElementIndex = null;
                            lastClickHadError = false;
                            consecutiveFailures = maxRetries;
                            // Fall through to the failure/replan handler below
                        }
                    }
                }

                // ── Step E: Skip re-fill of non-empty / already-filled fields ──────
                // Clear the filled-set when the observation changes (new page).
                if (!Objects.equals(lastObservationId, filledObservationId)) {
                    filledInCurrentObservation.clear();
                    filledObservationId = lastObservationId;
                }
                if ("FILL".equals(decision.actionType()) && decision.args() != null) {
                    Object eidx = decision.args().get("element_index");
                    if (eidx != null) {
                        int idx = eidx instanceof Number n ? n.intValue() : Integer.parseInt(eidx.toString());
                        // Skip if already successfully filled in this observation cycle
                        if (filledInCurrentObservation.contains(idx)) {
                            log.info("Task {} step {} | FILL SKIPPED — element {} already filled this observation",
                                taskId, step, idx);
                            recentActions.add("FILL_SKIPPED: element " + idx + " already filled");
                            continue;
                        }
                        // Skip if element has a visible non-empty value (non-password fields)
                        if (lastInteractiveElements != null && idx >= 0 && idx < lastInteractiveElements.size()) {
                            String currentValue = String.valueOf(lastInteractiveElements.get(idx).getOrDefault("value", ""));
                            if (!currentValue.isBlank() && !"***".equals(currentValue)) {
                                log.info("Task {} step {} | FILL SKIPPED — element {} already has value",
                                    taskId, step, idx);
                                recentActions.add("FILL_SKIPPED: element " + idx + " already has value");
                                continue;
                            }
                        }
                    }
                }

                // No-progress detection: fingerprint = actionType + sorted key args + obsHash
                String fingerprint = computeActionFingerprint(decision, currentUrl);
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

                // ── Step G: Confirm-before-submit gate ────────────────────────
                // Force approval on CLICK targeting submit-like buttons, even if the AI forgot.
                // Only check the actual element text/label, NOT the AI's description (avoids false positives).
                if ("CLICK".equals(decision.actionType()) && !decision.requiresApproval()
                        && decision.args() != null) {
                    String btnText = "";
                    Object eidx = decision.args().get("element_index");
                    if (eidx != null && lastInteractiveElements != null) {
                        int idx = eidx instanceof Number n ? n.intValue() : Integer.parseInt(eidx.toString());
                        if (idx >= 0 && idx < lastInteractiveElements.size()) {
                            Map<String, Object> btnEl = lastInteractiveElements.get(idx);
                            btnText = String.valueOf(btnEl.getOrDefault("text", ""));
                            if (btnText.isBlank()) btnText = String.valueOf(btnEl.getOrDefault("label", ""));
                            String btnRole = String.valueOf(btnEl.getOrDefault("role", ""));
                            // Only apply to button-role elements, not links
                            if (!"button".equals(btnRole)) btnText = "";
                        }
                    }
                    if (!btnText.isBlank() && SUBMIT_BUTTON_PATTERN.matcher(btnText).find()) {
                        log.info("Task {} step {} | CONFIRM-BEFORE-SUBMIT forced for '{}'", taskId, step, btnText.trim());
                        decision = new AiProvider.ActionDecision(
                            decision.actionType(), decision.args(), decision.rationale(),
                            true, "Confirm before submitting: " + btnText.trim());
                    }
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
                // Persist reasoning schema fields from AI response
                if (decision.observation() != null) taskStep.setObservationText(decision.observation());
                if (decision.pageState() != null) taskStep.setPageState(decision.pageState());
                if (decision.why() != null) taskStep.setWhyText(decision.why());
                if (decision.expected() != null) taskStep.setExpectedText(decision.expected());
                taskStepRepository.save(taskStep);

                publishEvent(TaskEvent.stepUpdate(taskId, "RUNNING",
                    decision.rationale() + " → " + decision.actionType(), step, Map.of()));

                // Re-check cancellation before sending action to worker
                if (isCancelled(taskId)) {
                    log.info("Task {} cancelled before action execution at step {}", taskId, step);
                    break;
                }

                // Resolve credential and user-value placeholders in FILL values before sending to worker.
                // The model uses __cred_password:domain__ and __user:key__ placeholders;
                // actual values never enter the LLM prompt.
                if ("FILL".equals(decision.actionType()) && decision.args() != null) {
                    Object fillValue = decision.args().get("value");
                    if (fillValue instanceof String sv) {
                        String resolved = resolveCredentialPlaceholder(sv, taskCredentials);
                        resolved = resolveUserPlaceholder(resolved, knownValues);
                        if (!sv.equals(resolved)) {
                            Map<String, Object> patchedArgs = new HashMap<>(decision.args());
                            patchedArgs.put("value", resolved);
                            decision = new AiProvider.ActionDecision(
                                decision.actionType(), patchedArgs, decision.rationale(),
                                decision.requiresApproval(), decision.approvalReason());
                        }
                    }
                }

                // Hard dispatch check: block FILL if value still contains an unresolved placeholder.
                if ("FILL".equals(decision.actionType()) && decision.args() != null) {
                    Object fillVal = decision.args().get("value");
                    if (fillVal instanceof String sv && ANY_UNRESOLVED_PLACEHOLDER.matcher(sv).find()) {
                        log.error("Task {} step {} | FILL BLOCKED — unresolved placeholder '{}' not in knownValues keys={}",
                            taskId, step, sv, knownValues.keySet());
                        recentActions.add("FILL_BLOCKED: unresolved placeholder, key not in knownValues");
                        continue;
                    }
                }

                // ── Step D: Personal-data FILL guard ────────────────────────────
                // Reject FILL on a personal field unless the value is a placeholder
                // (__cred_* or __user:*__), appears in the task text, or is in knownValues.
                if ("FILL".equals(decision.actionType()) && decision.args() != null) {
                    String fillDesc = String.valueOf(decision.args().getOrDefault("description", ""));
                    String fillValue = String.valueOf(decision.args().getOrDefault("value", ""));
                    if (isPersonalDataField(fillDesc) && !isValueFromKnownSource(fillValue, knownValues, task.getUserRequest())) {
                        log.warn("Task {} step {} | FILL REJECTED — personal field '{}' value not from known source",
                            taskId, step, fillDesc);
                        publishEvent(TaskEvent.stepUpdate(taskId, "RUNNING",
                            "Blocked invented data for '" + fillDesc + "' — triggering ASK_USER", step, Map.of()));

                        List<Map<String, Object>> askFields = detectMissingFields(lastInteractiveElements, knownValues, task.getUserRequest());
                        if (askFields.isEmpty()) {
                            askFields = List.of(Map.of("label", fillDesc, "name", normalizeFieldKey(fillDesc), "type", "text", "required", true));
                        }
                        String askReason = "The form requires personal information that was not provided in the task.";
                        Map<String, Object> approvalParams = new HashMap<>();
                        approvalParams.put("type", "form_input_request");
                        approvalParams.put("fields", askFields);
                        approvalParams.put("reason", askReason);

                        ApprovalRequest approval = ApprovalRequest.builder()
                            .taskId(taskId).prompt(askReason).proposedAction("ASK_USER")
                            .parameters(approvalParams).status("PENDING").build();
                        approval = approvalRepository.save(approval);

                        PauseResult pauseResult = pauseForUser(task, sessionId, approval.getId(),
                            askReason, "ASK_USER", step, approvalParams);
                        if (!pauseResult.resumed) { failTask(task, pauseResult.failureReason()); return; }

                        ApprovalRequest resolved = approvalRepository.findById(approval.getId()).orElse(null);
                        if (resolved != null && resolved.getUserResponse() != null) {
                            storeUserValues(resolved.getUserResponse(), knownValues, taskId);
                            credentialHints = buildFullHints(taskCredentials, knownValues);
                        }
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
                        recentActions.add("FILL_REJECTED → ASK_USER: personal data not in known sources");
                        continue;
                    }
                }

                // (Step E moved to before no-progress detector)

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

                // ── Step F: Per-action structured log (mask personal values) ──
                {
                    Object eidx = actionArgs.get("element_index");
                    Object obsId = actionArgs.get("observation_id");
                    String detail = "";
                    if ("FILL".equals(decision.actionType())) {
                        String desc = String.valueOf(actionArgs.getOrDefault("description", "?"));
                        Object val = actionArgs.get("value");
                        String valStr = val instanceof String s ? s : "";
                        boolean isSecret = valStr.startsWith("__cred_") || isPersonalDataField(desc);
                        detail = " desc=\"" + desc + "\" value=" + (isSecret ? "[MASKED]" : "\"" + valStr + "\"");
                    } else if ("NAVIGATE".equals(decision.actionType())) {
                        detail = " url=\"" + truncate(String.valueOf(actionArgs.getOrDefault("url", "")), 80) + "\"";
                    }
                    log.info("Task {} step {} | DISPATCH {} | element_index={} obs_id={}{}",
                        taskId, step, decision.actionType(), eidx, obsId, detail);
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

                if (result.currentUrl() != null && !result.currentUrl().isBlank()) {
                    currentUrl = result.currentUrl();
                }
                if (result.pageTitle() != null) {
                    pageTitle = result.pageTitle();
                }
                if (result.pageText() != null) {
                    pageText = result.pageText();
                }
                if (result.pageState() != null) {
                    currentPageState = result.pageState();
                }
                if (result.screenshot() != null) {
                    lastScreenshot = result.screenshot();
                }

                // Handle bot_block BEFORE CAPTCHA — different options, never loops
                boolean hasBotBlock = "BOT_BLOCK".equalsIgnoreCase(currentPageState)
                    || (result.challenges() != null && result.challenges().stream()
                    .anyMatch(c -> "bot_block".equals(c.getOrDefault("type", ""))));
                if (hasBotBlock) {
                    currentPageState = "BOT_BLOCK";
                    log.warn("Task {} step {} | BOT_BLOCK at {}", taskId, step, truncate(currentUrl, 80));
                    String blockDomain = extractDomain(currentUrl);
                    if (blockDomain != null) {
                        blockedDomains.add(blockDomain);
                        setDomainCooldown(blockDomain, Duration.ofMinutes(15));
                    }
                    if (result.screenshot() != null) {
                        saveEvidence(taskId, "STEP_SCREENSHOT",
                            "Bot block at " + truncate(currentUrl, 60), result.screenshot(), currentUrl);
                    }
                    Map<String, Object> bbParams = new HashMap<>();
                    bbParams.put("type", "bot_block");
                    bbParams.put("url", currentUrl);
                    bbParams.put("reason", "The site returned an access-denial page.");
                    bbParams.put("options", List.of(
                        "open_live_view: Open the live browser view",
                        "skip: Skip this source and try another",
                        "stop: Stop the task"));
                    String bbReason = "Bot block detected at " + truncate(currentUrl, 60);
                    ApprovalRequest bbApproval = findOrCreateApproval(taskId, "BOT_BLOCK", bbReason, bbParams);
                    PauseResult bbPause = pauseForUser(task, sessionId, bbApproval.getId(),
                        bbReason, "BOT_BLOCK", step, bbParams);
                    if (!bbPause.resumed) { failTask(task, bbPause.failureReason()); return; }
                    if (bbPause.observationId != null) {
                        lastObservationId = bbPause.observationId;
                        lastInteractiveElements = bbPause.interactiveElements;
                    }
                    if (bbPause.currentUrl != null) currentUrl = bbPause.currentUrl;
                    if (bbPause.pageTitle != null) pageTitle = bbPause.pageTitle;
                    if (bbPause.pageText != null) {
                        pageText = bbPause.pageText;
                        lastObservationHash = computeObservationHash(currentUrl, pageTitle, pageText, lastInteractiveElements);
                        observationEpoch++;
                    }
                    if (bbPause.pageState() != null) {
                        currentPageState = bbPause.pageState();
                    }
                    ApprovalRequest bbResolved = approvalRepository.findById(bbApproval.getId()).orElse(null);
                    String bbChoice = bbResolved != null && bbResolved.getUserResponse() != null
                        ? bbResolved.getUserResponse().toLowerCase() : "stop";
                    if (bbChoice.contains("stop")) {
                        failTask(task, "BOT_BLOCK: user chose to stop at " + truncate(currentUrl, 60));
                        return;
                    }
                    recentActions.add("BOT_BLOCK: user chose " + bbChoice + " at " + truncate(currentUrl, 50));
                    continue;
                }

                // Handle CAPTCHA/2FA challenges: pause for user, DO NOT attempt to solve
                List<Map<String, Object>> captchaChallenges = result.challenges() == null ? List.of()
                    : result.challenges().stream()
                        .filter(c -> !"bot_block".equals(c.getOrDefault("type", "")))
                        .collect(Collectors.toList());
                if (!captchaChallenges.isEmpty()) {
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
                        captchaChallenges.get(0).getOrDefault("type", "unknown") + " challenge");
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
                    // Track successfully filled element indices (Step E)
                    if ("FILL".equals(decision.actionType()) && decision.args() != null) {
                        Object eidx = decision.args().get("element_index");
                        if (eidx != null) {
                            int idx = eidx instanceof Number n ? n.intValue() : Integer.parseInt(eidx.toString());
                            filledInCurrentObservation.add(idx);
                        }
                    }
                    // Track CLICK result: record element index and whether the resulting page has errors.
                    // Used by the repeated-failed-CLICK guard above.
                    if ("CLICK".equals(decision.actionType()) && decision.args() != null) {
                        Object eidx = decision.args().get("element_index");
                        if (eidx != null) {
                            int idx = eidx instanceof Number n ? n.intValue() : Integer.parseInt(eidx.toString());
                            String newPageText = result.pageText() != null ? result.pageText() : "";
                            boolean hasError = !extractPageErrorText(newPageText).isBlank();
                            lastClickElementIndex = idx;
                            lastClickHadError = hasError;
                        }
                    }

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

                    // ── Early success detection ──────────────────────────────────────
                    // After CLICK or NAVIGATE check if success criteria are met.
                    if ("CLICK".equals(decision.actionType()) || "NAVIGATE".equals(decision.actionType())) {
                        if (meetsSuccessCriteria(task.getWorkflowType().name(),
                                task.getSuccessCriteria(), pageText, currentUrl, textSnippets)) {
                            log.info("Task {} step {} | SUCCESS CRITERIA MET — breaking to synthesize", taskId, step);
                            publishEvent(TaskEvent.stepUpdate(taskId, "RUNNING",
                                "Task complete — synthesizing result", step, Map.of()));
                            recentActions.add("SUCCESS_CRITERIA_MET");
                            break;
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

            // Step G: Use unsupportedClaims — if any exist, downgrade to PARTIAL
            boolean hasUnsupported = verification.unsupportedClaims() != null
                && !verification.unsupportedClaims().isEmpty();
            if (hasUnsupported) {
                log.warn("Task {} — {} unsupported claims in verification: {}",
                    taskId, verification.unsupportedClaims().size(), verification.unsupportedClaims());
            }

            String verificationDetails = "Passed: " + verification.passed() + "\n" + verification.notes();
            if (hasUnsupported) {
                verificationDetails += "\nUnsupported claims: " + String.join("; ", verification.unsupportedClaims());
            }
            saveEvidence(taskId, "VERIFICATION_RESULT", "Verification", verificationDetails, null);

            // Step 6: Save result and transition to final state
            List<TaskResult.SourceRef> sources = visitedUrls.stream()
                .filter(u -> u != null && !u.isBlank())
                .map(u -> new TaskResult.SourceRef(u, ""))
                .toList();

            // Downgrade to PARTIAL if unsupported claims found, even if AI said passed
            boolean effectivelyPassed = verification.passed() && !hasUnsupported;

            // Automation Transparency & Official Alternatives report
            if (!blockedDomains.isEmpty() || !officialAlternatives.isEmpty()) {
                StringBuilder botReport = new StringBuilder("\n\n### Automation Transparency & Official Alternatives\n");
                if (!blockedDomains.isEmpty()) {
                    botReport.append("- **Sites that blocked automation:** ").append(String.join(", ", blockedDomains)).append("\n");
                    botReport.append("- **What was tried:** Standard polite browser automation (no CAPTCHA solving, token farming, or proxy rotation was used).\n");
                }
                if (!officialAlternatives.isEmpty()) {
                    botReport.append("- **Official alternatives available:**\n");
                    for (Map.Entry<String, String> entry : officialAlternatives.entrySet()) {
                        botReport.append("  - ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
                    }
                }
                synthesized += botReport.toString();
            }

            TaskResult taskResult = TaskResult.builder()
                .taskId(taskId)
                .summary(synthesized)
                .deliverableType(task.getDeliverableType())
                .verificationPassed(effectivelyPassed)
                .verificationNotes(verification.notes())
                .confidenceScore(verification.confidence())
                .sources(sources)
                .build();
            taskResultRepository.save(taskResult);

            TaskStatus finalStatus = effectivelyPassed ? TaskStatus.COMPLETED : TaskStatus.PARTIAL;
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

            if (routePreferenceService != null) {
                String navDomain = extractDomain(url);
                if (navDomain != null && isDomainInCooldown(navDomain)) {
                    Duration remaining = getDomainCooldownRemaining(navDomain);
                    String cooldownMsg = String.format("Domain %s is currently in cooldown for %ds after an access block.",
                        navDomain, remaining.toSeconds());
                    log.warn("Task {} NAVIGATE to {} rejected: {}", task.getId(), url, cooldownMsg);
                    publishEvent(TaskEvent.stepUpdate(task.getId(), "RUNNING", cooldownMsg, task.getCurrentStep(), Map.of()));
                    return false;
                }
                RoutePreferenceService.RoutePreferenceResult navPref = routePreferenceService.evaluate(url, task.getUserRequest());
                if (navPref != null && !navPref.isCanProceed()) {
                    log.warn("Task {} NAVIGATE to {} blocked by robots.txt: {}", task.getId(), url, navPref.getStopReason());
                    publishEvent(TaskEvent.stepUpdate(task.getId(), "RUNNING", navPref.getStopReason(), task.getCurrentStep(), Map.of()));
                    return false;
                }
            }
        }

        return true;
    }

    // ─── Personal-data / form gate helpers ──────────────────────────────────────

    private static final java.util.regex.Pattern PERSONAL_FIELD_PATTERN = java.util.regex.Pattern.compile(
        "(?i)(first.?name|last.?name|full.?name|\\bname\\b|email|e-mail|phone|mobile|address|zip|postal|city|state|country|dob|birth|ssn|credit.?card)"
    );

    private static final java.util.regex.Pattern SUBMIT_BUTTON_PATTERN = java.util.regex.Pattern.compile(
        "(?i)(finish|place.?order|submit|complete|confirm|pay|buy|checkout|send|purchase)"
    );

    // ─── Auth surface detection ───────────────────────────────────────────────────

    record AuthSurface(
        boolean hasPasswordForm,    // input[type=password] + identifier field present
        boolean hasEmailOnlyForm,   // email input but no password
        List<String> oauthProviders,// detected SSO provider names (Google, Apple, etc.)
        boolean hasPhoneOtp,        // tel/OTP input
        boolean isAuthPage          // any of the above is true
    ) {}

    private static final java.util.regex.Pattern OAUTH_PROVIDER_PATTERN =
        java.util.regex.Pattern.compile(
            "(?i)\\b(google|apple|facebook|github|microsoft|twitter|linkedin|slack|gitlab|bitbucket|yahoo)\\b"
        );
    private static final java.util.regex.Pattern OAUTH_URL_PATTERN =
        java.util.regex.Pattern.compile(
            "(?i)(accounts\\.google\\.com|appleid\\.apple\\.com|connect\\.facebook|github\\.com/login/oauth|login\\.microsoftonline|twitter\\.com/i/oauth)"
        );

    private static AuthSurface detectAuthSurface(
            List<Map<String, Object>> elements, String pageText, String url) {
        if (elements == null) return new AuthSurface(false, false, List.of(), false, false);
        boolean hasPassword = false;
        boolean hasIdentifier = false;
        Set<String> oauth = new LinkedHashSet<>();
        boolean hasPhoneOtp = false;

        for (Map<String, Object> el : elements) {
            String type = String.valueOf(el.getOrDefault("type", ""));
            String role = String.valueOf(el.getOrDefault("role", ""));
            String label = String.valueOf(el.getOrDefault("label", "")).toLowerCase();
            String text = String.valueOf(el.getOrDefault("text", "")).toLowerCase();
            String href = el.get("href") != null ? el.get("href").toString() : "";
            String elName = el.get("name") != null ? el.get("name").toString().toLowerCase() : "";
            String autocomplete = el.get("autocomplete") != null ? el.get("autocomplete").toString().toLowerCase() : "";

            if ("password".equals(type) || "current-password".equals(autocomplete)) {
                hasPassword = true;
            }
            if ("email".equals(type) || "email".equals(autocomplete)
                    || label.contains("email") || elName.contains("email")
                    || label.contains("username") || label.contains("user name")
                    || elName.contains("username") || elName.contains("user")
                    || "username".equals(autocomplete)) {
                hasIdentifier = true;
            }
            if (("tel".equals(type) || "tel".equals(autocomplete))
                    && (label.contains("phone") || label.contains("mobile")
                        || elName.contains("phone") || elName.contains("otp")
                        || elName.contains("code"))) {
                hasPhoneOtp = true;
            }
            if ("button".equals(role) || "link".equals(role)) {
                String combined = text + " " + label;
                java.util.regex.Matcher om = OAUTH_PROVIDER_PATTERN.matcher(combined);
                while (om.find()) oauth.add(capitalizeFirst(om.group(1)));
                if (!href.isEmpty() && OAUTH_URL_PATTERN.matcher(href).find()) {
                    java.util.regex.Matcher hm = OAUTH_PROVIDER_PATTERN.matcher(combined + " " + href);
                    while (hm.find()) oauth.add(capitalizeFirst(hm.group(1)));
                    if (oauth.isEmpty()) oauth.add("SSO");
                }
            }
        }
        if (pageText != null) {
            String sample = pageText.substring(0, Math.min(2000, pageText.length()));
            java.util.regex.Matcher pm = OAUTH_PROVIDER_PATTERN.matcher(sample);
            while (pm.find()) {
                String p = capitalizeFirst(pm.group(1));
                if (!oauth.contains(p) && oauth.size() < 8) oauth.add(p);
            }
        }
        boolean hasEmailOnly = hasIdentifier && !hasPassword && !hasPhoneOtp;
        boolean isAuth = hasPassword || hasEmailOnly || !oauth.isEmpty() || hasPhoneOtp;
        return new AuthSurface(hasPassword && hasIdentifier, hasEmailOnly,
            new ArrayList<>(oauth), hasPhoneOtp, isAuth);
    }

    private static String capitalizeFirst(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase();
    }

    private void handleAuthGateResponse(String jsonResponse, String domain,
            Map<String, String> taskCredentials, Map<String, String> knownValues,
            Set<String> authHandledDomains, UUID taskId) {
        authHandledDomains.add(domain);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> resp = new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(jsonResponse, Map.class);
            String choice = String.valueOf(resp.getOrDefault("choice", "handoff"));
            if ("password".equals(choice)) {
                String username = String.valueOf(resp.getOrDefault("username", ""));
                String password = String.valueOf(resp.getOrDefault("password", ""));
                if (!username.isBlank() && !password.isBlank()) {
                    taskCredentials.put(domain, username + "\n" + password);
                    log.info("Task {} — AUTH GATE: credentials provided for {}", taskId, domain);
                }
            } else if ("email_link".equals(choice)) {
                String email = String.valueOf(resp.getOrDefault("email", ""));
                if (!email.isBlank()) {
                    knownValues.put("email", email);
                    log.info("Task {} — AUTH GATE: email provided for {}", taskId, domain);
                }
            } else {
                log.info("Task {} — AUTH GATE: handoff choice for {} — user handles auth", taskId, domain);
            }
        } catch (Exception e) {
            log.warn("Task {} — AUTH GATE: could not parse auth response: {}", taskId, e.getMessage());
        }
    }

    /**
     * Check whether the auth surface is gone and at least one logged-in marker is present.
     * Returns true when we're confident the user is authenticated.
     */
    private static boolean verifyLoggedIn(
            List<Map<String, Object>> elements, String pageText, String url, AuthSurface authSurface) {
        if (elements == null) return false;
        AuthSurface current = detectAuthSurface(elements, pageText, url);
        if (current.isAuthPage()) return false; // still on an auth page

        final java.util.regex.Pattern LOGGED_IN_MARKERS = java.util.regex.Pattern.compile(
            "(?i)\\b(sign\\s*out|log\\s*out|logout|my\\s+account|my\\s+profile|profile|dashboard|settings|account\\s+settings|avatar|notifications\\s+for|welcome|hello)\\b"
        );
        for (Map<String, Object> el : elements) {
            String combined = el.getOrDefault("label", "") + " "
                + el.getOrDefault("text", "") + " "
                + (el.get("href") != null ? el.get("href") : "");
            if (LOGGED_IN_MARKERS.matcher(combined).find()) return true;
        }
        if (pageText != null && LOGGED_IN_MARKERS.matcher(
                pageText.substring(0, Math.min(1000, pageText.length()))).find()) {
            return true;
        }
        // URL left the typical auth path — treat as likely success
        if (url != null && !url.toLowerCase().matches(
                ".*/(login|signin|sign-in|auth|account/login|user/login).*")) {
            return true;
        }
        return false;
    }

    // ─── Confirmation signal patterns ─────────────────────────────────────────────

    /**
     * Generic patterns that indicate a task has reached its final confirmation state.
     * Site-agnostic: matches standard confirmation signals across any domain.
     */
    private static final java.util.regex.Pattern CONFIRMATION_SIGNAL_PATTERN = java.util.regex.Pattern.compile(
        "(?i)\\b(thank\\s+you|order\\s+(confirmed|placed|received|complete)|confirmation|"
        + "successfully\\s+(submitted|placed|completed|sent|saved)|receipt|"
        + "reference\\s+(number|#|no)|case\\s+(id|number|#)|ticket\\s+(id|number|#)|"
        + "booking\\s+(confirmed|reference)|payment\\s+(confirmed|accepted|successful)|"
        + "your\\s+order)\\b"
    );

    private static final Set<String> PERSONAL_AUTOCOMPLETE_TOKENS = Set.of(
        "given-name", "family-name", "name", "email", "tel", "tel-national",
        "address-line1", "address-line2", "address-level1", "address-level2",
        "postal-code", "country-name", "bday", "cc-name", "cc-number"
    );

    private static boolean isPersonalDataField(String fieldDescription) {
        if (fieldDescription == null || fieldDescription.isBlank()) return false;
        return PERSONAL_FIELD_PATTERN.matcher(fieldDescription).find();
    }

    // ─── User-value placeholder patterns (before methods that reference them) ───────
    private static final java.util.regex.Pattern USER_PLACEHOLDER_COLON = java.util.regex.Pattern.compile(
        "__user:([a-z0-9_]+)__"
    );
    private static final java.util.regex.Pattern USER_PLACEHOLDER_UNDER = java.util.regex.Pattern.compile(
        "__user_([a-z0-9_]+)__"
    );
    /** Detects any un-resolved placeholder remaining after resolution attempts. */
    private static final java.util.regex.Pattern ANY_UNRESOLVED_PLACEHOLDER = java.util.regex.Pattern.compile(
        "__[a-z][a-z0-9:._]+__"
    );

    private static boolean isValueFromKnownSource(String value, Map<String, String> knownValues, String userRequest) {
        if (value == null || value.isBlank()) return true;
        String v = value.trim();
        if ("Demo User".equalsIgnoreCase(v)) return true;
        if (v.startsWith("__cred_")) return true;
        // User placeholder: OK only if every referenced key exists in knownValues
        if (v.contains("__user")) {
            java.util.regex.Matcher mc = USER_PLACEHOLDER_COLON.matcher(v);
            while (mc.find()) {
                String key = normalizeFieldKey(mc.group(1));
                if (knownValues == null || !knownValues.containsKey(key)) return false;
            }
            java.util.regex.Matcher mu = USER_PLACEHOLDER_UNDER.matcher(v);
            while (mu.find()) {
                String key = normalizeFieldKey(mu.group(1));
                if (knownValues == null || !knownValues.containsKey(key)) return false;
            }
            return true;
        }
        if (knownValues != null && knownValues.containsValue(v)) return true;
        if (userRequest != null && userRequest.toLowerCase().contains(v.toLowerCase())) return true;
        return false;
    }

    /**
     * Detect empty form fields that need user input.
     * Uses canonical key resolution (autocomplete > name > label) and the real
     * required flag from the element. Never asks again for keys already in knownValues.
     */
    private static List<Map<String, Object>> detectMissingFields(
            List<Map<String, Object>> interactiveElements,
            Map<String, String> knownValues,
            String userRequest) {
        if (interactiveElements == null) return List.of();
        List<Map<String, Object>> fields = new ArrayList<>();
        Set<String> seenKeys = new HashSet<>();
        for (Map<String, Object> el : interactiveElements) {
            String role = String.valueOf(el.getOrDefault("role", ""));
            if (!"textbox".equals(role) && !"combobox".equals(role)) continue;

            // Skip fields that already have a visible non-empty value
            String value = String.valueOf(el.getOrDefault("value", ""));
            if (!value.isBlank() && !"***".equals(value)) continue;

            // Skip sensitive fields — handled by credential gate
            String type = String.valueOf(el.getOrDefault("type", ""));
            if ("password".equals(type)) continue;
            String elAc = el.get("autocomplete") != null ? el.get("autocomplete").toString().toLowerCase() : "";
            if (elAc.equals("current-password") || elAc.equals("new-password")
                    || elAc.equals("cc-number") || elAc.equals("cc-csc") || elAc.equals("cc-exp")) continue;
            String elName = el.get("name") != null ? el.get("name").toString().toLowerCase() : "";
            if (elName.equals("username") || elName.equals("user") || elName.equals("login")
                    || elAc.equals("username")) continue;

            boolean isRequired = Boolean.TRUE.equals(el.get("required"))
                || "true".equals(String.valueOf(el.getOrDefault("ariaRequired", "")));
            String autocomplete = el.get("autocomplete") != null ? el.get("autocomplete").toString() : "";
            String label = el.getOrDefault("label", "").toString();
            String placeholder = el.get("placeholder") != null ? el.get("placeholder").toString() : "";
            String hint = !label.isBlank() ? label : (!placeholder.isBlank() ? placeholder : elName);
            if (hint.isBlank()) continue;

            boolean isPersonalAc = !autocomplete.isBlank()
                && PERSONAL_AUTOCOMPLETE_TOKENS.contains(autocomplete.toLowerCase());
            boolean isPersonalLabel = PERSONAL_FIELD_PATTERN.matcher(hint).find();
            if (!isRequired && !isPersonalAc && !isPersonalLabel) continue;

            // Canonical key — dedup within this scan
            String canonicalKey = canonicalKeyForElement(el);
            if (canonicalKey.isBlank()) continue;
            if (seenKeys.contains(canonicalKey)) continue;
            seenKeys.add(canonicalKey);

            // Skip if already known (by canonical key or hint slug)
            if (knownValues.containsKey(canonicalKey)) continue;
            if (knownValues.containsKey(normalizeFieldKey(hint))) continue;
            if (userRequest != null && userRequest.toLowerCase().contains(hint.toLowerCase())) continue;

            java.util.LinkedHashMap<String, Object> field = new java.util.LinkedHashMap<>();
            field.put("label", hint);
            field.put("name", canonicalKey);
            field.put("type", "text");
            field.put("required", isRequired);
            fields.add(field);
        }
        return fields;
    }

    // ─── Canonical field key mapping ─────────────────────────────────────────────

    /** Synonym map: raw slug → canonical key. Site-agnostic; no hardcoded page strings. */
    private static final Map<String, String> FIELD_SYNONYMS;
    static {
        Map<String, String> m = new java.util.LinkedHashMap<>();
        for (String k : List.of("zip", "zipcode", "zip_code", "postal", "postcode",
                "post_code", "pin", "pincode", "pin_code", "postal_code_zip"))
            m.put(k, "postal_code");
        for (String k : List.of("mobile", "cell", "cellphone", "contact_number",
                "contact", "telephone", "phone_number", "mobile_number"))
            m.put(k, "phone");
        for (String k : List.of("first", "given_name", "forename", "first_name", "given"))
            m.put(k, "first_name");
        for (String k : List.of("last", "surname", "family_name", "family", "last_name"))
            m.put(k, "last_name");
        for (String k : List.of("full_name", "fullname", "name")) m.put(k, "full_name");
        for (String k : List.of("e_mail", "email_address", "mail")) m.put(k, "email");
        for (String k : List.of("town", "locality", "city_name")) m.put(k, "city");
        for (String k : List.of("province", "region", "county", "state_province")) m.put(k, "state");
        for (String k : List.of("street", "street_address", "address_line1", "address1")) m.put(k, "address");
        FIELD_SYNONYMS = java.util.Collections.unmodifiableMap(m);
    }

    /** W3C autocomplete token → canonical key. Checked before name/label. */
    private static final Map<String, String> AUTOCOMPLETE_TO_KEY = Map.ofEntries(
        Map.entry("given-name", "first_name"),
        Map.entry("additional-name", "middle_name"),
        Map.entry("family-name", "last_name"),
        Map.entry("name", "full_name"),
        Map.entry("email", "email"),
        Map.entry("tel", "phone"),
        Map.entry("tel-national", "phone"),
        Map.entry("street-address", "address"),
        Map.entry("address-line1", "address"),
        Map.entry("address-line2", "address_line2"),
        Map.entry("address-level1", "state"),
        Map.entry("address-level2", "city"),
        Map.entry("postal-code", "postal_code"),
        Map.entry("country", "country"),
        Map.entry("country-name", "country"),
        Map.entry("cc-name", "card_name"),
        Map.entry("cc-number", "card_number"),
        Map.entry("cc-exp", "card_expiry"),
        Map.entry("cc-csc", "card_cvv"),
        Map.entry("bday", "date_of_birth"),
        Map.entry("username", "username"),
        Map.entry("current-password", "password"),
        Map.entry("new-password", "new_password"),
        Map.entry("organization", "company"),
        Map.entry("honorific-prefix", "title")
    );

    /**
     * Normalize a raw string to a canonical field key.
     * Applies the synonym table so "Zip", "postal code", "PIN" all yield postal_code.
     */
    private static String normalizeFieldKey(String fieldHint) {
        if (fieldHint == null || fieldHint.isBlank()) return "";
        String slug = fieldHint.toLowerCase().replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "");
        return FIELD_SYNONYMS.getOrDefault(slug, slug);
    }

    /**
     * Derive a canonical key from a full element map using priority:
     * autocomplete attribute > name/id > label/placeholder text.
     */
    private static String canonicalKeyForElement(Map<String, Object> el) {
        String ac = el.get("autocomplete") != null ? el.get("autocomplete").toString().toLowerCase().trim() : "";
        if (!ac.isBlank()) {
            String acKey = AUTOCOMPLETE_TO_KEY.get(ac);
            if (acKey != null) return acKey;
            return normalizeFieldKey(ac);
        }
        String name = el.get("name") != null ? el.get("name").toString() : "";
        if (!name.isBlank()) return normalizeFieldKey(name);
        String id = el.get("id") != null ? el.get("id").toString() : "";
        if (!id.isBlank()) return normalizeFieldKey(id);
        String label = el.getOrDefault("label", "").toString();
        String placeholder = el.get("placeholder") != null ? el.get("placeholder").toString() : "";
        String hint = !label.isBlank() ? label : placeholder;
        return normalizeFieldKey(hint);
    }

    private static final java.util.regex.Pattern PAGE_ERROR_PATTERN = java.util.regex.Pattern.compile(
        "(?i)\\b(error|required|invalid|failed|denied|incorrect|wrong|try\\s+again|not\\s+found|please\\s+(enter|fill|provide)|field\\s+is\\s+(required|empty)|cannot\\s+be\\s+blank|must\\s+(not\\s+be\\s+empty|be\\s+filled))\\b"
    );

    /** Extract up to 200 chars of error-like text from a page text blob. */
    private static String extractPageErrorText(String pageText) {
        if (pageText == null || pageText.isBlank()) return "";
        return java.util.Arrays.stream(pageText.split("\n"))
            .filter(line -> PAGE_ERROR_PATTERN.matcher(line).find())
            .map(String::trim)
            .filter(l -> !l.isBlank())
            .limit(3)
            .collect(java.util.stream.Collectors.joining("; "))
            .transform(s -> s.length() > 200 ? s.substring(0, 200) : s);
    }

    /**
     * Parse ASK_USER JSON response and store values in knownValues (Step C).
     */
    private void storeUserValues(String jsonResponse, Map<String, String> knownValues, UUID taskId) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, String> userValues = new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(jsonResponse, Map.class);
            for (Map.Entry<String, String> entry : userValues.entrySet()) {
                String key = normalizeFieldKey(entry.getKey());
                knownValues.put(key, entry.getValue());
            }
            log.info("Task {} — stored {} user values in knownValues", taskId, userValues.size());
        } catch (Exception e) {
            log.warn("Task {} — could not parse ASK_USER response: {}", taskId, e.getMessage());
        }
    }

    /**
     * Build the full hints block for the AI prompt: credential placeholders + user-value placeholders.
     * Actual values NEVER appear in the prompt (Step C).
     */
    private String buildFullHints(Map<String, String> taskCredentials, Map<String, String> knownValues) {
        StringBuilder sb = new StringBuilder();
        String credHints = buildCredentialHints(taskCredentials);
        if (credHints != null) sb.append(credHints);
        if (!knownValues.isEmpty()) {
            sb.append("\nUSER-PROVIDED VALUES (use these exact placeholders in FILL value):\n");
            for (String key : knownValues.keySet()) {
                sb.append("  Value available: __user:").append(key).append("__\n");
            }
            sb.append("IMPORTANT: Use the exact __user:key__ placeholder. The backend resolves it to the actual value.\n");
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    // ─── User-value placeholder resolution (Step C) ───────────────────────────

    /**
     * Replace all __user:key__ and __user_key__ occurrences using find()+appendReplacement
     * so placeholders inside a longer string resolve too.
     * Normalises the captured key via normalizeFieldKey before the knownValues lookup.
     */
    private static String resolveUserPlaceholder(String value, Map<String, String> knownValues) {
        if (value == null || !value.contains("__")) return value;
        value = resolvePatternOccurrences(value, USER_PLACEHOLDER_COLON, knownValues);
        value = resolvePatternOccurrences(value, USER_PLACEHOLDER_UNDER, knownValues);
        return value;
    }

    private static String resolvePatternOccurrences(String input, java.util.regex.Pattern p,
            Map<String, String> knownValues) {
        java.util.regex.Matcher m = p.matcher(input);
        if (!m.find()) return input;
        StringBuffer sb = new StringBuffer();
        do {
            String rawKey = m.group(1);
            String key = normalizeFieldKey(rawKey);
            String resolved = knownValues.get(key);
            if (resolved != null) {
                log.debug("[placeholder] resolved __user:{}__ from knownValues", key);
                m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(resolved));
            } else {
                m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(m.group(0)));
            }
        } while (m.find());
        m.appendTail(sb);
        return sb.toString();
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
        java.util.List<java.util.Map<String, Object>> interactiveElements,
        String pageState
    ) {
        static PauseResult failed(String reason) {
            return new PauseResult(false, reason, null, null, null, null, null, "NORMAL");
        }
        static PauseResult success(ActionResult unparkResult) {
            return new PauseResult(true, null,
                unparkResult.currentUrl(), unparkResult.pageTitle(),
                unparkResult.pageText(), unparkResult.observationId(),
                unparkResult.interactiveElements(), unparkResult.pageState());
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
                if (unparkResult == null || !unparkResult.success()) {
                    String err = unparkResult != null && unparkResult.error() != null ? unparkResult.error() : "null unpark response";
                    return PauseResult.failed("Browser session lost during wait: " + err);
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
     * Extract credentials from the vault or task instruction text if available.
     * Auth gates are now handled at observation time via detectAuthSurface() —
     * this method no longer pauses and asks the user interactively.
     *
     * SECURITY: credentials from the instruction are used for THIS RUN ONLY and never persisted.
     */
    CredentialGateResult checkCredentialGate(Task task, List<String> planSteps, String sessionId) {
        UUID taskId = task.getId();
        String userRequest = task.getUserRequest();
        String targetDomain = extractDomain(userRequest);

        // 1. Check vault for stored credentials
        if (targetDomain != null && credentialVaultService.hasDomain(targetDomain)) {
            log.info("Task {} — vault has credentials for domain={}", taskId, targetDomain);
            var resolved = credentialVaultService.resolve(targetDomain);
            if (resolved.isPresent()) {
                Map<String, String> creds = new HashMap<>();
                creds.put(targetDomain, resolved.get().username() + "\n" + resolved.get().password());
                return new CredentialGateResult(true, true, creds);
            }
        }

        // 2. Extract credentials from instruction text (user passed them inline)
        Map<String, String> extractedCreds = extractCredentialsFromInstruction(userRequest);
        if (!extractedCreds.isEmpty()) {
            log.info("Task {} — credentials found in instruction for domain(s): {}", taskId, extractedCreds.keySet());
            return new CredentialGateResult(true, true, extractedCreds);
        }

        // No pre-supplied credentials — auth gate will handle it at observation time
        return CredentialGateResult.noop();
    }

    private String extractDomain(String text) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
            "(?:https?://)?([a-zA-Z0-9][-a-zA-Z0-9]*\\.[a-zA-Z]{2,}(?:\\.[a-zA-Z]{2,})?)"
        ).matcher(text);
        return m.find() ? m.group(1).toLowerCase() : null;
    }

    private String extractTargetUrl(String text) {
        if (text == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
            "https?://[^\\s\"'<>]+"
        ).matcher(text);
        if (m.find()) {
            return m.group(0);
        }
        String domain = extractDomain(text);
        return domain != null ? "https://" + domain : null;
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

    private String computeActionFingerprint(AiProvider.ActionDecision decision, String currentUrl) {
        String type = decision.actionType();
        Map<String, Object> args = decision.args() != null ? decision.args() : Map.of();
        // FILL: fingerprint by element_index only (different values don't count as progress)
        if ("FILL".equals(type)) {
            Object eidx = args.get("element_index");
            return type + "|element_index=" + eidx;
        }
        // EXTRACT_TEXT + SCREENSHOT: fingerprint by type + URL only; description/selector are cosmetic
        if ("EXTRACT_TEXT".equals(type) || "SCREENSHOT".equals(type)) {
            return type + "|url=" + (currentUrl != null ? currentUrl : "");
        }
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

    private static final int MAX_CHALLENGE_RETRIES = 3;

    /**
     * Return true if page text still shows a challenge-still-present signal.
     * Uses generic phrases, no site-specific strings.
     */
    private static boolean challengeStillDetected(List<Map<String, Object>> elements, String pageText) {
        if (pageText == null) return false;
        String lower = pageText.toLowerCase();
        return lower.contains("verify you are human")
            || lower.contains("just a moment")
            || lower.contains("press & hold")
            || lower.contains("access denied")
            || lower.contains("unusual traffic");
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
        String basePrompt = isOtp
            ? "2FA/OTP verification code required"
            : "CAPTCHA detected" + (provider != null && !provider.isEmpty() ? " (" + provider + ")" : "")
                + " — please solve it in the browser";

        log.info("Task {} — challenge detected: type={} provider={}", taskId, firstType, provider);

        Map<String, Object> params = new HashMap<>();
        params.put("type", approvalType);
        params.put("challengeType", firstType);
        if (provider != null) params.put("provider", provider);
        if (inputName != null) params.put("inputName", inputName);

        ApprovalRequest approval = findOrCreateApproval(taskId, approvalType.toUpperCase(), basePrompt, params);

        PauseResult pauseResult = null;

        for (int attempt = 1; attempt <= MAX_CHALLENGE_RETRIES; attempt++) {
            String prompt = attempt == 1 ? basePrompt
                : basePrompt + " (still showing — please finish it, attempt " + attempt + "/" + MAX_CHALLENGE_RETRIES + ")";

            if (attempt > 1) {
                // Reset approval to PENDING so the card re-appears
                approval.setStatus("PENDING");
                approval.setResolvedAt(null);
                approval.setUserResponse(null);
                approvalRepository.save(approval);
            }

            pauseResult = pauseForUser(task, sessionId, approval.getId(),
                prompt, approvalType.toUpperCase(), currentStep, params);

            if (!pauseResult.resumed) {
                return ChallengeHandleResult.failed(pauseResult.failureReason());
            }

            // OTP: fill code and return immediately — no re-verify loop
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
                return new ChallengeHandleResult(true, null,
                    pauseResult.currentUrl(), pauseResult.pageTitle(),
                    pauseResult.pageText(), pauseResult.observationId(),
                    pauseResult.interactiveElements());
            }

            // CAPTCHA: re-observe to check whether the challenge is actually gone
            String freshPageText = pauseResult.pageText() != null ? pauseResult.pageText() : "";
            if (!challengeStillDetected(pauseResult.interactiveElements(), freshPageText)) {
                log.info("Task {} — challenge cleared on attempt {}", taskId, attempt);
                break; // challenge cleared
            }

            if (attempt == MAX_CHALLENGE_RETRIES) {
                return ChallengeHandleResult.failed(
                    "Challenge still present after " + MAX_CHALLENGE_RETRIES + " attempts");
            }
            log.warn("Task {} — challenge still present (attempt {}/{}), re-showing card",
                taskId, attempt, MAX_CHALLENGE_RETRIES);
        }

        // CAPTCHA resolved — resume with unparked state
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

    /**
     * Check whether the current page state satisfies the task's success criteria.
     * Site-agnostic: uses the task's planned criteria keywords and generic confirmation
     * signals. Research tasks use data-completeness instead of page signals.
     */
    private boolean meetsSuccessCriteria(String workflowType, String successCriteria,
            String pageText, String currentUrl, List<String> textSnippets) {
        // Research/comparison tasks: done when sufficient distinct content collected (no confirmation page)
        if ("WEB_RESEARCH".equals(workflowType) || "PRODUCT_COMPARISON".equals(workflowType)) {
            return textSnippets.size() >= 3 && textSnippets.stream().filter(s -> s.length() > 200).count() >= 2;
        }
        // UI_INTERACTION: only trigger on generic confirmation signals (thank you, order confirmed, etc.).
        // Do NOT use keyword-matching against success criteria — inventory/product pages share many words
        // (order, shirt, checkout) with the success criteria but are not completion pages.
        if (pageText != null && CONFIRMATION_SIGNAL_PATTERN.matcher(pageText).find()) return true;
        return false;
    }

    /**
     * Idempotent approval creation: reuse an existing PENDING approval of the same type
     * for this task if one already exists, avoiding duplicate cards on retries.
     */
    private ApprovalRequest findOrCreateApproval(UUID taskId, String proposedAction,
            String prompt, Map<String, Object> parameters) {
        return approvalRepository
            .findFirstByTaskIdAndProposedActionAndStatus(taskId, proposedAction, "PENDING")
            .orElseGet(() -> approvalRepository.save(ApprovalRequest.builder()
                .taskId(taskId).prompt(prompt).proposedAction(proposedAction)
                .parameters(parameters).status("PENDING").build()));
    }

    private String getKeyPoolState() {
        if (aiProvider instanceof GeminiAiProvider gp) {
            return gp.getKeyPool().getKeyStates().toString();
        }
        return "N/A";
    }

    /**
     * Build a deduplication key for an approval request.
     * Two approvals with the same proposed action and the same sorted set of field names
     * are considered equivalent — the second one reuses the first answer.
     */
    private static String approvalDedupKey(String proposedAction, Map<String, Object> parameters) {
        if (parameters == null) return proposedAction + ":{}";
        Object fieldsObj = parameters.get("fields");
        String fieldHash = "";
        if (fieldsObj instanceof List<?> flist) {
            fieldHash = flist.stream()
                .filter(f -> f instanceof Map)
                .map(f -> {
                    Object name = ((Map<?, ?>) f).get("name");
                    return name != null ? name.toString() : "";
                })
                .filter(s -> !s.isBlank())
                .sorted()
                .collect(Collectors.joining(","));
        }
        return proposedAction + ":" + fieldHash;
    }
}
