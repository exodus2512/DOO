package com.doo.ai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import jakarta.annotation.PostConstruct;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@Primary
public class GeminiAiProvider implements AiProvider {

    private static final String GEMINI_BASE_URL = "https://generativelanguage.googleapis.com/v1beta";
    private static final Set<String> KNOWN_MODELS = Set.of(
        "gemini-3.8-flash", "gemini-3.7-flash", "gemini-3.6-flash",
        "gemini-3.5-flash", "gemini-3.5-flash-lite"
    );

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final String model;
    private final boolean demoMode;
    private final GeminiKeyPool keyPool;
    private final GeminiThrottle throttle;
    private final int maxRetries;

    private final ConcurrentHashMap<String, AiCallStats> activeStats = new ConcurrentHashMap<>();

    @Autowired
    public GeminiAiProvider(
        @Value("${doo.ai.gemini.api-key:}") String apiKey,
        @Value("${doo.ai.gemini.model:gemini-3.8-flash}") String model,
        @Value("${doo.ai.demo-mode:false}") boolean demoMode,
        ObjectMapper objectMapper,
        @Value("${doo.ai.gemini.api-key-2:}") String apiKey2,
        @Value("${doo.ai.gemini.api-key-3:}") String apiKey3,
        @Value("${doo.ai.gemini.min-call-interval-ms:4000}") long minIntervalMs,
        @Value("${doo.ai.gemini.max-rpm:10}") int maxRpm,
        @Value("${doo.ai.gemini.key-cooldown-seconds:120}") long cooldownSeconds,
        @Value("${doo.ai.gemini.max-retries:2}") int maxRetries
    ) {
        this.model = model;
        this.demoMode = demoMode;
        this.objectMapper = objectMapper;
        this.maxRetries = maxRetries;
        this.webClient = WebClient.builder()
            .baseUrl(GEMINI_BASE_URL)
            .defaultHeader("Content-Type", "application/json")
            .build();

        List<String> allKeys = new ArrayList<>();
        allKeys.add(apiKey);
        allKeys.add(apiKey2);
        allKeys.add(apiKey3);
        this.keyPool = new GeminiKeyPool(allKeys, Duration.ofSeconds(cooldownSeconds));
        this.throttle = new GeminiThrottle(
            Duration.ofMillis(minIntervalMs),
            maxRpm
        );

        log.info("GeminiAiProvider: model={}, demoMode={}, keys={}, minInterval={}ms, maxRpm={}, cooldown={}s",
            model, demoMode, keyPool.size(), minIntervalMs, maxRpm, cooldownSeconds);
    }

    @PostConstruct
    void validateModel() {
        if (!KNOWN_MODELS.contains(model)) {
            log.error("Configured model '{}' is not in the known-good list {}. " +
                "This will likely produce 404 errors. Switching to gemini-3.8-flash.", model, KNOWN_MODELS);
        }
    }

    public GeminiAiProvider(String apiKey, String model, boolean demoMode, ObjectMapper objectMapper) {
        this(apiKey, model, demoMode, objectMapper, "", "", 100, 60, 10, 2);
    }

    private boolean isAvailable() {
        return !demoMode && keyPool.hasKeys();
    }

    public AiCallStats getOrCreateStats(String taskLabel) {
        return activeStats.computeIfAbsent(taskLabel, AiCallStats::new);
    }

    public void clearStats(String taskLabel) {
        activeStats.remove(taskLabel);
    }

    // ─── AiProvider interface ──────────────────────────────────────────────

    @Override
    public WorkflowClassification classifyWorkflow(String userRequest) {
        if (!isAvailable()) {
            log.info("[DEMO MODE] Using deterministic workflow classification");
            return deterministic_classifyWorkflow(userRequest);
        }
        String prompt = """
            You are DOO, a general-purpose AI browser agent. Classify this user request.

            User request: "%s"

            Workflow types:
            - WEB_RESEARCH: needs live web data — research, comparison, lead lookup, pricing, audits, reviews, dashboard reads, study plans. Most tasks that need a browser.
            - UI_INTERACTION: the user wants you to fill forms, submit data, interact with a specific page, or complete a multi-step workflow on a site.
            - LLM_ONLY: static knowledge questions, explanations, summaries, math, code help — answerable without a browser.
            - UNKNOWN: genuinely ambiguous — you cannot determine the goal.

            Respond in JSON format ONLY (no markdown, no explanation outside JSON):
            {
              "workflowType": "WEB_RESEARCH|UI_INTERACTION|LLM_ONLY|UNKNOWN",
              "rationale": "one sentence explanation",
              "successCriteria": "specific, testable conditions for task completion",
              "goal": "one-sentence restatement of what the user wants",
              "entities": ["key nouns/names the task is about"],
              "deliverable": "what artifact to produce: comparison_table | report | bug_list | draft | form_submission | data_extract | answer",
              "missingInputs": ["anything the user didn't specify that you'll need to ask or assume"]
            }
            """.formatted(userRequest);

        try {
            String taskLabel = truncate(userRequest, 60);
            log.info("[STEP 1/5] CLASSIFY — starting for: {}", taskLabel);
            getOrCreateStats(taskLabel).record(AiCallStats.Phase.CLASSIFY);
            String response = callGemini(prompt, taskLabel);
            JsonNode json = objectMapper.readTree(extractJson(response));
            String wfType = json.path("workflowType").asText("UNKNOWN");
            List<String> entities = new ArrayList<>();
            json.path("entities").forEach(n -> entities.add(n.asText()));
            List<String> missingInputs = new ArrayList<>();
            json.path("missingInputs").forEach(n -> missingInputs.add(n.asText()));
            log.info("[STEP 1/5] CLASSIFY — result: {} deliverable={} entities={}", wfType,
                json.path("deliverable").asText(""), entities);
            return new WorkflowClassification(
                wfType,
                json.path("rationale").asText(""),
                json.path("successCriteria").asText(""),
                json.path("goal").asText(""),
                entities,
                json.path("deliverable").asText(""),
                missingInputs
            );
        } catch (Exception e) {
            log.error("[STEP 1/5] CLASSIFY — FAILED: {}", e.getMessage());
            throw new RuntimeException("AI provider error during classification: " + e.getMessage(), e);
        }
    }

    @Override
    public TaskPlan generatePlan(String userRequest, String successCriteria, WorkflowContext context) {
        if (!isAvailable()) {
            log.info("[DEMO MODE] Using deterministic plan generation");
            return deterministic_generatePlan(userRequest, context.workflowType());
        }
        String prompt = """
            You are DOO, a general-purpose AI browser agent. Create a focused execution plan.

            User request: "%s"
            Workflow type: %s
            Success criteria: %s

            STEP TYPES you can use (prefix each step with its type):
            - NAVIGATE: go to a specific URL or search engine
            - SEARCH: use a search engine to find information
            - EXTRACT: pull structured data from the current page (price, features, text, links)
            - INTERACT: fill a form field, click a button, select an option
            - COMPARE: visit multiple sources and extract the same schema from each for comparison
            - ASK_USER: pause and ask the user for input (credentials, personal data, choices)
            - SYNTHESIZE: combine extracted data into the deliverable (table, report, list)
            - VERIFY: check the final result against evidence and success criteria

            Rules:
            - Maximum 8 steps. Every step must advance the task.
            - Each step starts with its TYPE in brackets, e.g. "[NAVIGATE] Go to google.com"
            - For research/comparison: search → visit 2-3 sources → extract from each → synthesize.
            - For form filling: navigate → observe form → ASK_USER for any unknown personal data → fill → submit → verify.
            - For QA/audit: navigate → systematically check pages/links/forms → extract issues → synthesize report.
            - NEVER invent personal data (name, email, phone, address). Use ASK_USER instead.
            - Do NOT include steps that require payment or destructive actions.
            - If comparing multiple items, plan to visit each item's page separately.

            Respond in JSON format ONLY:
            {
              "steps": ["[TYPE] step description", "[TYPE] step description", ...],
              "successCriteria": "refined testable criteria"
            }
            """.formatted(userRequest, context.workflowType(), successCriteria);

        try {
            String taskLabel = truncate(userRequest, 60);
            log.info("[STEP 2/5] PLAN — generating for: {}", taskLabel);
            getOrCreateStats(taskLabel).record(AiCallStats.Phase.PLAN);
            String response = callGemini(prompt, taskLabel);
            JsonNode json = objectMapper.readTree(extractJson(response));
            List<String> steps = new ArrayList<>();
            json.path("steps").forEach(n -> steps.add(n.asText()));
            log.info("[STEP 2/5] PLAN — {} steps generated", steps.size());
            return new TaskPlan(steps, json.path("successCriteria").asText(successCriteria));
        } catch (Exception e) {
            log.error("[STEP 2/5] PLAN — FAILED: {}", e.getMessage());
            throw new RuntimeException("AI provider error during planning: " + e.getMessage(), e);
        }
    }

    @Override
    public ActionDecision selectNextAction(ActionSelectionRequest request) {
        if (!isAvailable()) {
            return deterministic_selectAction(request);
        }
        // Build the page state block: prefer indexed elements, fall back to raw text
        String pageStateBlock;
        if (request.observationId() != null && request.interactiveElements() != null
                && !request.interactiveElements().isEmpty()) {
            StringBuilder sb = new StringBuilder();
            sb.append("Observation ID: ").append(request.observationId()).append("\n");
            sb.append("Interactive elements (use element_index to act on them):\n");
            for (java.util.Map<String, Object> el : request.interactiveElements()) {
                Object idx = el.get("index");
                Object tag = el.get("tag") != null ? el.get("tag") : (el.get("type") != null ? el.get("type") : "element");
                Object role = el.get("role");
                Object label = el.get("label");
                Object text = el.get("text");
                Object value = el.get("value");
                Object placeholder = el.get("placeholder");
                Object type = el.get("type");
                Object href = el.get("href");
                sb.append(String.format("  [%s] <%s> role=%s", idx, tag, role));
                if (label != null && !label.toString().isBlank()) sb.append(" label=\"").append(label).append("\"");
                if (text != null && !text.toString().isBlank()) sb.append(" text=\"").append(text).append("\"");
                if (value != null && !value.toString().isBlank()) sb.append(" value=\"").append(value).append("\"");
                if (placeholder != null && !placeholder.toString().isBlank()) sb.append(" placeholder=\"").append(placeholder).append("\"");
                if ("a".equals(tag) && href != null) sb.append(" href=\"").append(href).append("\"");
                sb.append("\n");
            }
            pageStateBlock = sb.toString();
        } else {
            pageStateBlock = "Page text (truncated, no structured observation available):\n"
                + truncate(request.pageText(), 2000);
        }

        String obsIdLine = request.observationId() != null
            ? "\nobservation_id (REQUIRED in args for CLICK/FILL/SELECT_OPTION/PRESS_KEY): \"" + request.observationId() + "\""
            : "";

        String credBlock = request.credentialHints() != null ? request.credentialHints() : "";

        String scrollBlock = "";
        if (request.scroll() != null) {
            Object scrollY = request.scroll().getOrDefault("scrollY", 0);
            Object pageHeight = request.scroll().getOrDefault("pageHeight", 0);
            Object viewportHeight = request.scroll().getOrDefault("viewportHeight", 0);
            scrollBlock = String.format("Scroll position: %s/%spx (viewport %spx)", scrollY, pageHeight, viewportHeight);
        }

        String extractionProgress = request.extractedSnippetCount() > 0
            ? "Extracted snippets so far: " + request.extractedSnippetCount()
            : "";

        String prompt = """
            You are DOO, an AI browser agent selecting the next browser action.

            Task: "%s"
            Current plan step %d: %s
            Current URL: %s
            Page title: %s
            %s
            %s
            Recent actions: %s
            %s

            Select ONE action. Respond in JSON format ONLY:
            {
              "actionType": "NAVIGATE|CLICK|FILL|PRESS_KEY|EXTRACT_TEXT|SCREENSHOT|SCROLL|WAIT|DONE|ASK_USER",
              "args": { ... action-specific args ... },
              "rationale": "why this action",
              "requiresApproval": false,
              "approvalReason": ""
            }

            Action args by type:
            - NAVIGATE: {"url": "https://..."}
            - CLICK: {"element_index": 5, "observation_id": "...", "description": "what element"}
            - FILL: {"element_index": 3, "observation_id": "...", "value": "text to enter", "description": "field name"}
            - PRESS_KEY: {"key": "Enter|Tab|Escape|Backspace|ArrowDown|ArrowUp|Space"}
            - SELECT_OPTION: {"element_index": 7, "observation_id": "...", "value": "option text"}
            - EXTRACT_TEXT: {"selector": "body", "label": "what we're extracting"}
            - SCREENSHOT: {"label": "description of what we're capturing"}
            - SCROLL: {"direction": "down|up", "amount": 500}
            - WAIT: {"milliseconds": 1000}
            - DONE: {"reason": "why we're done"}
            - ASK_USER: {"fields": [{"label": "field name", "type": "text|email|tel|select|date|radio", "required": true, "options": ["opt1","opt2"]}], "reason": "why we need user input"}

            CRITICAL: For CLICK, FILL, SELECT_OPTION — you MUST use element_index from the elements list above.
            Never invent CSS selectors. The element_index is the [number] in brackets from the list.
            Always include observation_id in args when using element_index.

            WORKFLOW PATTERNS:
            - Search: FILL search box → PRESS_KEY Enter → EXTRACT_TEXT on results
            - Multi-field form: FILL each required empty field in order → CLICK submit when all filled
            - Research: NAVIGATE to source → EXTRACT_TEXT structured data → repeat for next source
            - Comparison: visit each item's page → EXTRACT_TEXT same fields from each → let SYNTHESIZE combine
            - QA audit: NAVIGATE each page → check links/forms/content → EXTRACT_TEXT findings
            - Pagination: after extracting, look for "Next" or page number links and CLICK to continue
            - Long page: if scroll position shows content below viewport, SCROLL down → EXTRACT_TEXT → repeat
            - Multi-tab: use GO_BACK to return after visiting a sub-page, rather than re-navigating from scratch

            RULES:
            - FILL only puts text into a single field. It does NOT submit the form.
            - Check the 'value="..."' attribute of each element. If a field already has a value (or '***'), DO NOT fill it again.
            - DO NOT press Enter after filling just one field of a multi-field form.
            - For single-field search inputs (type=search or a lone input), follow FILL with PRESS_KEY Enter.
            - If a previous action returned RESOLVE_FAILED, pick from the current valid indices.
            - When logging into a site, FILL credentials using the credential placeholders below, then CLICK the submit button.
            - Use EXTRACT_TEXT as soon as useful content is visible. Always extract before DONE.
            - You MUST use EXTRACT_TEXT at least once before DONE.
            - For EXTRACT_TEXT, set the "label" field to describe WHAT you're extracting (e.g. "pricing table", "product features", "form error messages").
            - Never navigate to file:// URLs or private IPs.

            SAFETY GATES — set requiresApproval=true for any of these:
            - Submitting a form that sends data externally (contact form, signup, purchase, subscription)
            - Clicking buy/purchase/subscribe/delete/send/publish buttons
            - Any action that is irreversible or externally visible
            - If unsure whether an action is safe, set requiresApproval=true with a clear approvalReason.
            Read-only actions (NAVIGATE, EXTRACT_TEXT, SCREENSHOT, SCROLL) NEVER need approval.

            FORM HANDLING:
            - If the page has a form with required fields that need personal data you don't have (name, email, phone, address), set actionType="ASK_USER" with args listing the fields.
            - ASK_USER args: {"fields": [{"label": "Full Name", "type": "text", "required": true}, ...], "reason": "Form requires personal information"}
            - NEVER invent personal data. Always ASK_USER for data you don't have.
            %s
            """.formatted(
                request.userRequest(),
                request.currentStep(),
                request.planSteps().isEmpty() ? "N/A" : request.planSteps().get(
                    Math.min(request.currentStep(), request.planSteps().size() - 1)),
                request.currentUrl(),
                request.pageTitle(),
                pageStateBlock,
                scrollBlock + (extractionProgress.isEmpty() ? "" : "\n            " + extractionProgress),
                String.join(", ", request.recentActions().stream().limit(5).toList()),
                obsIdLine,
                credBlock
            );

        String taskLabel = truncate(request.userRequest(), 60);
        log.info("[STEP 3/5] SELECT_ACTION — step {}, url={}", request.currentStep(), truncate(request.currentUrl(), 80));
        getOrCreateStats(taskLabel).record(AiCallStats.Phase.SELECT_ACTION);

        for (int jsonRetry = 0; jsonRetry < 2; jsonRetry++) {
            try {
                String response = callGemini(prompt, taskLabel);
                JsonNode json = objectMapper.readTree(extractJson(response));
                String actionType = json.path("actionType").asText("SCREENSHOT");
                Map<String, Object> args = objectMapper.convertValue(
                    json.path("args"), new TypeReference<Map<String, Object>>() {});
                log.info("[STEP 3/5] SELECT_ACTION — chose: {}", actionType);
                return new ActionDecision(
                    actionType,
                    args != null ? args : Map.of(),
                    json.path("rationale").asText(""),
                    json.path("requiresApproval").asBoolean(false),
                    json.path("approvalReason").asText("")
                );
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                log.warn("[STEP 3/5] SELECT_ACTION — malformed JSON from model, retrying ({}/2)", jsonRetry + 1);
                if (jsonRetry == 1) throw new RuntimeException("AI provider error during action selection: " + e.getMessage(), e);
            } catch (Exception e) {
                log.error("[STEP 3/5] SELECT_ACTION — FAILED: {}", e.getMessage());
                throw new RuntimeException("AI provider error during action selection: " + e.getMessage(), e);
            }
        }
        throw new RuntimeException("AI provider error during action selection: exhausted retries");
    }

    @Override
    public VerificationResult verify(VerificationRequest request) {
        if (!isAvailable()) {
            boolean hasEvidence = request.evidenceSummary() != null && !request.evidenceSummary().isEmpty();
            boolean hasUrls = request.visitedUrls() != null && !request.visitedUrls().isEmpty();
            boolean passed = hasEvidence && hasUrls;
            String notes = passed
                ? "Demo mode: verification passed based on collected evidence — " + String.join("; ", request.evidenceSummary())
                : "Demo mode: insufficient evidence collected";
            return new VerificationResult(passed, notes, passed ? 0.7 : 0.3);
        }
        String evidenceBlock = request.evidenceSummary() != null
            ? String.join("\n", request.evidenceSummary()) : "No evidence summary available";
        String prompt = """
            You are DOO's verification engine. Determine if the task was completed successfully.

            User request: "%s"
            Success criteria: "%s"
            Workflow type: %s
            Final URL: %s
            Visited URLs: %s
            Synthesized result (truncated): %s

            Evidence collected:
            %s

            VERIFICATION RULES:
            - Base your verdict on the specific evidence listed above.
            - Check that every claim in the synthesized result has a matching evidence item (URL, text snippet, or screenshot).
            - Flag any claim that appears to be invented (not supported by evidence) — list it in unsupportedClaims.
            - If evidence is insufficient to confirm the success criteria, mark as NOT passed.
            - Cite specific screenshot numbers and extracted text snippets in your notes.
            - Check that facts labeled "confirmed" actually appear in the evidence.

            Did the execution meet the success criteria? Respond in JSON format ONLY:
            {
              "passed": true|false,
              "notes": "specific explanation citing screenshots and text evidence",
              "confidence": 0.0-1.0
            }
            """.formatted(
                request.userRequest(),
                request.successCriteria(),
                request.workflowType(),
                request.finalUrl(),
                String.join(", ", request.visitedUrls()),
                truncate(request.synthesizedResult(), 3000),
                evidenceBlock
            );

        try {
            String taskLabel = truncate(request.userRequest(), 60);
            log.info("[STEP 5/5] VERIFY — checking against: {}", truncate(request.successCriteria(), 80));
            getOrCreateStats(taskLabel).record(AiCallStats.Phase.VERIFY);
            String response = callGemini(prompt, taskLabel);
            JsonNode json = objectMapper.readTree(extractJson(response));
            boolean passed = json.path("passed").asBoolean(false);
            double confidence = json.path("confidence").asDouble(0.5);
            log.info("[STEP 5/5] VERIFY — passed={}, confidence={}", passed, confidence);
            return new VerificationResult(
                passed,
                json.path("notes").asText(""),
                confidence
            );
        } catch (Exception e) {
            log.error("[STEP 5/5] VERIFY — FAILED: {}", e.getMessage());
            throw new RuntimeException("AI provider error during verification: " + e.getMessage(), e);
        }
    }

    @Override
    public String synthesizeResult(SynthesisRequest request) {
        if (!isAvailable()) {
            log.info("[DEMO MODE] Using deterministic result synthesis");
            return deterministic_synthesizeResult(request);
        }
        String deliverableHint = request.deliverableType() != null ? request.deliverableType() : "auto";
        String prompt = """
            You are DOO. Synthesize a structured deliverable from the evidence collected.

            User request: "%s"
            Workflow: %s
            Requested deliverable format: %s
            Visited URLs: %s
            Evidence collected:
            %s

            DELIVERABLE FORMATS (pick based on "Requested deliverable format" above):
            - comparison_table → Markdown table with columns per entity. Include "Source" column. Each cell shows confirmed/estimated/unavailable.
            - report → Markdown sections with headings. Cite sources inline.
            - bug_list → Numbered list: issue, severity, reproduction steps, screenshot reference, URL.
            - draft → Clearly labeled DRAFT content (post, email, etc.).
            - data_extract → Structured data (JSON code block or Markdown table).
            - form_submission → Summary of what was submitted and confirmation status.
            - answer → Direct answer with citations.
            - auto → Infer the best format from the user request.

            OUTPUT RULES:
            1. Use Markdown formatting appropriate to the deliverable format above.
            2. Every fact must cite its source: "[Source: URL]" or "[Evidence: snippet label]".
            3. Classify each fact as:
               - **Confirmed**: directly seen on a page (cite the URL)
               - **Estimated**: inferred or approximate (explain why)
               - **Unavailable**: could not be found (say so, don't guess)
            4. For comparison tables, include a "Source" column with the URL for each row.
            5. For QA/audit reports, include: issue description, reproduction steps, severity, screenshot reference.
            6. For draft content (posts, emails), clearly label it as a DRAFT.
            7. NEVER invent facts not present in the evidence. Say "unavailable" for gaps.
            8. End with a "## Sources" section listing all URLs visited.
            """.formatted(
                request.userRequest(),
                request.workflowType(),
                deliverableHint,
                String.join(", ", request.visitedUrls()),
                truncate(String.join("\n\n---\n\n", request.textSnippets()), 6000)
            );

        try {
            String taskLabel = truncate(request.userRequest(), 60);
            log.info("[STEP 4/5] SYNTHESIZE — {} URLs, {} snippets", request.visitedUrls().size(), request.textSnippets().size());
            getOrCreateStats(taskLabel).record(AiCallStats.Phase.SYNTHESIZE);
            String result = callGemini(prompt, taskLabel);
            log.info("[STEP 4/5] SYNTHESIZE — done, {} chars", result.length());
            return result;
        } catch (Exception e) {
            log.error("Live AI provider failed to synthesize result: {}", e.getMessage());
            throw new RuntimeException("AI provider error during synthesis: " + e.getMessage(), e);
        }
    }

    @Override
    public String directAnswer(String userRequest) {
        if (!isAvailable()) {
            return "This is a static knowledge question answered in demo mode. " +
                "In live mode, the LLM would provide a direct answer for: " + userRequest;
        }
        String prompt = """
            Answer the following question directly and concisely. Use markdown formatting.
            If you are not confident in the answer, say so. Do not make up facts.

            Question: "%s"
            """.formatted(userRequest);

        try {
            String taskLabel = truncate(userRequest, 60);
            log.info("[LLM_ONLY] Answering directly: {}", taskLabel);
            getOrCreateStats(taskLabel).record(AiCallStats.Phase.SYNTHESIZE);
            return callGemini(prompt, taskLabel);
        } catch (Exception e) {
            log.error("[LLM_ONLY] Failed: {}", e.getMessage());
            throw new RuntimeException("AI provider error during direct answer: " + e.getMessage(), e);
        }
    }

    @Override
    public TaskPlan replan(ReplanRequest request) {
        if (!isAvailable()) {
            return new TaskPlan(List.of(
                "[NAVIGATE] Go to a search engine",
                "[EXTRACT] Try alternative approach",
                "[SYNTHESIZE] Produce result from available data"
            ), "Recover from failure and complete the task");
        }
        String prompt = """
            You are DOO, a general-purpose AI browser agent. The original plan hit a problem and you need to replan.

            User request: "%s"
            Workflow type: %s
            Original plan steps: %s
            Failed at step %d: %s
            Current URL: %s
            Current page title: %s
            Actions completed so far: %s

            Create a NEW plan that picks up from the current state. Do NOT repeat steps that already succeeded.
            Use the same step type prefixes: [NAVIGATE], [SEARCH], [EXTRACT], [INTERACT], [COMPARE], [ASK_USER], [SYNTHESIZE], [VERIFY].

            Rules:
            - Maximum 6 remaining steps.
            - If the failure was a navigation error, try an alternative URL or search approach.
            - If the failure was an interaction error, try a different element or approach.
            - If enough data has already been collected, skip to [SYNTHESIZE].
            - Do NOT repeat the exact same action that failed.

            Respond in JSON format ONLY:
            {
              "steps": ["[TYPE] step description", ...],
              "successCriteria": "refined testable criteria"
            }
            """.formatted(
                request.userRequest(),
                request.workflowType(),
                String.join("; ", request.originalSteps()),
                request.failedAtStep(),
                request.failureReason(),
                request.currentUrl() != null ? request.currentUrl() : "none",
                request.pageTitle() != null ? request.pageTitle() : "none",
                String.join("; ", request.completedActions().stream().limit(10).toList())
            );

        try {
            String taskLabel = truncate(request.userRequest(), 60);
            log.info("[REPLAN] Replanning after failure at step {}: {}", request.failedAtStep(), request.failureReason());
            getOrCreateStats(taskLabel).record(AiCallStats.Phase.PLAN);
            String response = callGemini(prompt, taskLabel);
            JsonNode json = objectMapper.readTree(extractJson(response));
            List<String> steps = new ArrayList<>();
            json.path("steps").forEach(n -> steps.add(n.asText()));
            log.info("[REPLAN] New plan: {} steps", steps.size());
            return new TaskPlan(steps, json.path("successCriteria").asText(""));
        } catch (Exception e) {
            log.error("[REPLAN] Failed: {}", e.getMessage());
            throw new RuntimeException("AI provider error during replanning: " + e.getMessage(), e);
        }
    }

    // ─── Core API caller with throttle, key rotation, backoff ──────────────

    String callGemini(String prompt, String taskLabel) {
        Exception lastException = null;
        GeminiKeyPool.KeyEntry stickyKey = null;
        int consecutive503 = 0;

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            GeminiKeyPool.KeyEntry keyEntry = stickyKey != null ? stickyKey : keyPool.nextHealthyKey();
            stickyKey = null;
            if (keyEntry == null) {
                throw new AllKeysExhaustedException("All API keys exhausted (dead or cooling down): " +
                    keyPool.getKeyStates());
            }

            log.info("[API] Using {} | model={} | attempt {}/{}", keyEntry.label(), model, attempt + 1, maxRetries + 1);

            try {
                throttle.acquirePermit();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while waiting for throttle permit", e);
            }
            log.info("[API] Throttle permit acquired, sending request...");

            if (attempt > 0) {
                AiCallStats stats = activeStats.get(taskLabel);
                if (stats != null) stats.record(AiCallStats.Phase.RETRY);
            }

            try {
                Map<String, Object> body = Map.of(
                    "contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))),
                    "generationConfig", Map.of(
                        "temperature", 0.2,
                        "maxOutputTokens", 4096
                    )
                );

                GeminiResponse geminiResponse = webClient.post()
                    .uri("/models/" + model + ":generateContent?key=" + keyEntry.key())
                    .bodyValue(body)
                    .exchangeToMono(response -> handleResponse(response, keyEntry))
                    .timeout(Duration.ofSeconds(30))
                    .block();

                if (geminiResponse == null || geminiResponse.body == null) {
                    throw new RuntimeException("Empty response from Gemini API");
                }

                if (geminiResponse.retryAfterSeconds > 0) {
                    long sleepMs = geminiResponse.retryAfterSeconds * 1000L;
                    log.warn("[API] 429 Retry-After={}s on {}, rotating key", geminiResponse.retryAfterSeconds, keyEntry.label());
                    keyPool.markCooldown(keyEntry.label(), "429 with Retry-After");
                    Thread.sleep(Math.min(sleepMs, 30_000));
                    lastException = new RuntimeException("429 Too Many Requests");
                    continue;
                }

                Map responseMap = objectMapper.readValue(geminiResponse.body, Map.class);
                List candidates = (List) responseMap.get("candidates");
                if (candidates == null || candidates.isEmpty()) {
                    throw new RuntimeException("No candidates in Gemini response");
                }

                Map candidate = (Map) candidates.get(0);
                Map content = (Map) candidate.get("content");
                if (content == null) throw new RuntimeException("No content in candidate");

                List parts = (List) content.get("parts");
                if (parts == null || parts.isEmpty()) throw new RuntimeException("No parts in content");

                String text = (String) ((Map) parts.get(0)).get("text");
                log.info("[API] Response OK from {} — {} chars", keyEntry.label(), text.length());
                consecutive503 = 0;
                return text;

            } catch (AllKeysExhaustedException e) {
                throw e;
            } catch (GeminiApiException e) {
                lastException = e;
                log.warn("[API] Error {}={} attempt {}/{}: {}", keyEntry.label(), e.statusCode, attempt + 1, maxRetries + 1, truncate(e.getMessage(), 120));

                if (e.statusCode == 401 || e.statusCode == 403) {
                    keyPool.markDead(keyEntry.label(), e.statusCode + " " + e.getMessage());
                    continue;
                }
                if (e.statusCode == 404) {
                    throw new RuntimeException("Model '" + model + "' not found (404). Check configured model ID.", e);
                }
                if (e.statusCode == 429) {
                    if (e.isDailyQuota) {
                        keyPool.markDead(keyEntry.label(), "Daily quota exhausted");
                        continue;
                    }
                    // Rate-limited: rotate to next key, short cooldown
                    keyPool.markCooldown(keyEntry.label(), "429 rate limit");
                    continue;
                }
                if (e.statusCode == 503 || e.statusCode == 500) {
                    consecutive503++;
                    if (consecutive503 >= 2) {
                        log.info("[API] {} — {} consecutive server errors on {}, rotating to next key", e.statusCode, consecutive503, keyEntry.label());
                        consecutive503 = 0;
                        stickyKey = null;
                    } else {
                        log.info("[API] {} — server overload, retrying same key after backoff", e.statusCode);
                        stickyKey = keyEntry;
                    }
                    if (attempt < maxRetries) {
                        long backoff = (long) Math.pow(2, attempt + 1) * 1000L + (long) (Math.random() * 1000);
                        try { Thread.sleep(Math.min(backoff, 15_000)); } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException("Interrupted during backoff", ie);
                        }
                    }
                    continue;
                }
                if (attempt < maxRetries) {
                    long backoff = (long) Math.pow(2, attempt + 1) * 1000L + (long) (Math.random() * 500);
                    try { Thread.sleep(backoff); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Interrupted during backoff", ie);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted during Gemini call", e);
            } catch (Exception e) {
                lastException = e;
                log.warn("Gemini call error ({}={}, attempt={}/{}): {}",
                    keyEntry.label(), model, attempt + 1, maxRetries + 1, e.getMessage());
                if (attempt < maxRetries) {
                    long backoff = (long) Math.pow(2, attempt + 1) * 1000L + (long) (Math.random() * 500);
                    try { Thread.sleep(backoff); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Interrupted during backoff", ie);
                    }
                }
            }
        }

        String msg = lastException != null ? lastException.getMessage() : "All attempts failed";
        log.error("All Gemini API attempts failed: {}", msg);
        throw new RuntimeException("AI provider error: " + msg, lastException);
    }

    private record GeminiResponse(String body, int retryAfterSeconds) {}

    private Mono<GeminiResponse> handleResponse(ClientResponse response, GeminiKeyPool.KeyEntry keyEntry) {
        HttpStatusCode status = response.statusCode();
        if (status.is2xxSuccessful()) {
            return response.bodyToMono(String.class)
                .map(body -> new GeminiResponse(body, 0));
        }

        int code = status.value();
        return response.bodyToMono(String.class).map(body -> {
            boolean isDailyQuota = body != null && (
                body.contains("PerDay") ||
                body.contains("per_day") ||
                (body.contains("quota") && body.contains("daily")) ||
                (body.contains("exceeded your current quota") && !body.contains("PerMinute"))
            );
            int retryAfter = 0;
            String retryHeader = response.headers().asHttpHeaders().getFirst("Retry-After");
            if (retryHeader != null) {
                try { retryAfter = Integer.parseInt(retryHeader); } catch (NumberFormatException ignored) {}
            }
            throw new GeminiApiException(code, body, isDailyQuota, retryAfter);
        });
    }

    static class GeminiApiException extends RuntimeException {
        final int statusCode;
        final boolean isDailyQuota;
        final int retryAfterSeconds;

        GeminiApiException(int statusCode, String body, boolean isDailyQuota, int retryAfterSeconds) {
            super(statusCode + " " + (body != null ? truncateStatic(body, 200) : ""));
            this.statusCode = statusCode;
            this.isDailyQuota = isDailyQuota;
            this.retryAfterSeconds = retryAfterSeconds;
        }
    }

    public static class AllKeysExhaustedException extends RuntimeException {
        public AllKeysExhaustedException(String msg) { super(msg); }
    }

    // ─── JSON + string helpers ──────────────────────────────────────────────

    private String extractJson(String text) {
        if (text == null) return "{}";
        text = text.trim();
        if (text.startsWith("```")) {
            int start = text.indexOf('\n') + 1;
            int end = text.lastIndexOf("```");
            if (end > start) text = text.substring(start, end).trim();
        }
        int start = -1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{' || c == '[') { start = i; break; }
        }
        if (start == -1) return "{}";
        return text.substring(start);
    }

    private String truncate(String text, int maxLength) {
        if (text == null) return "";
        return text.length() > maxLength ? text.substring(0, maxLength) + "..." : text;
    }

    private static String truncateStatic(String text, int maxLength) {
        if (text == null) return "";
        return text.length() > maxLength ? text.substring(0, maxLength) + "..." : text;
    }

    // ─── Deterministic fallbacks (demo/test mode) ──────────────────────────

    private WorkflowClassification deterministic_classifyWorkflow(String request) {
        String lower = request.toLowerCase();
        if (lower.contains("form") || lower.contains("fill") || lower.contains("submit")) {
            return new WorkflowClassification("UI_INTERACTION",
                "Request appears to be a UI interaction task",
                "Form submitted and confirmation page verified");
        }
        if (lower.contains("research") || lower.contains("compare") || lower.contains("difference") ||
            lower.contains("trending") || lower.contains("latest") || lower.contains("current")) {
            return new WorkflowClassification("WEB_RESEARCH",
                "Request appears to be a research task",
                "Result contains comparison with source URLs");
        }
        if (lower.contains("product") || lower.contains("price") || lower.contains("buy")) {
            return new WorkflowClassification("WEB_RESEARCH",
                "Request appears to be a product/price research task",
                "Each product has extracted specs and price with traceable sources");
        }
        if (lower.contains("what is") || lower.contains("explain") || lower.contains("how does") ||
            lower.contains("define") || lower.contains("list") || lower.contains("top ") ||
            lower.contains("summarize") || lower.contains("calculate") || lower.contains("translate")) {
            return new WorkflowClassification("LLM_ONLY",
                "Static knowledge question answerable without browser",
                "Answer is factually correct and addresses the question");
        }
        return new WorkflowClassification("WEB_RESEARCH",
            "Defaulting to web research", "Result contains relevant information");
    }

    private TaskPlan deterministic_generatePlan(String request, String workflowType) {
        return switch (workflowType) {
            case "WEB_RESEARCH" -> new TaskPlan(List.of(
                "Navigate to a search engine",
                "Search for the topic",
                "Visit the first relevant result",
                "Extract key information and note the URL",
                "Visit a second relevant source",
                "Extract additional information",
                "Synthesize findings into a structured answer"
            ), "Answer contains at least 2 sourced sections");
            case "PRODUCT_COMPARISON" -> new TaskPlan(List.of(
                "Navigate to a search engine",
                "Search for the first product or topic",
                "Extract key data points",
                "Navigate to compare with second source",
                "Extract comparison data",
                "Synthesize into a comparison table with source URLs"
            ), "All items have extracted data with traceable URLs");
            case "UI_INTERACTION" -> new TaskPlan(List.of(
                "Navigate to the test fixture",
                "Locate and fill Step 1 fields",
                "Proceed to Step 2",
                "Fill Step 2 fields",
                "Review and submit the form",
                "Capture confirmation screenshot",
                "Verify the final state"
            ), "Confirmation page is displayed with correct data");
            default -> new TaskPlan(List.of(
                "Navigate to a relevant starting point",
                "Observe current state",
                "Take action toward goal",
                "Verify outcome"
            ), "Task goal is achieved");
        };
    }

    private ActionDecision deterministic_selectAction(ActionSelectionRequest request) {
        if (request.currentUrl() == null || request.currentUrl().isBlank()) {
            String url = "UI_INTERACTION".equals(request.workflowType())
                ? "http://localhost:8081" : "https://en.wikipedia.org/wiki/Main_Page";
            return new ActionDecision("NAVIGATE",
                Map.of("url", url),
                "Starting navigation (demo mode)", false, "");
        }
        if ("UI_INTERACTION".equals(request.workflowType())) {
            if (request.currentStep() == 1) {
                // Use element_index if we have an observation, fall back to selector for tests
                if (request.observationId() != null && request.interactiveElements() != null) {
                    // Find the first text input element
                    int idx = 0;
                    for (java.util.Map<String, Object> el : request.interactiveElements()) {
                        Object role = el.get("role");
                        if ("textbox".equals(role) || "input".equals(el.get("tag"))) {
                            idx = ((Number) el.get("index")).intValue();
                            break;
                        }
                    }
                    return new ActionDecision("FILL",
                        Map.of("element_index", idx, "observation_id", request.observationId(),
                               "value", "Demo User", "description", "First text field"),
                        "Filling form field (demo mode)", false, "");
                }
                return new ActionDecision("FILL",
                    Map.of("selector", "#full-name", "value", "Demo User", "description", "Full Name"),
                    "Filling form field (demo mode)", false, "");
            }
            if (request.currentStep() >= 2) {
                return new ActionDecision("DONE",
                    Map.of("reason", "Demo mode: form interaction simulated"),
                    "Demo mode complete", false, "");
            }
            return new ActionDecision("SCREENSHOT",
                Map.of("label", "Current state observation"),
                "Capturing current state (demo mode)", false, "");
        }
        if (request.currentStep() >= 2) {
            return new ActionDecision("DONE",
                Map.of("reason", "Demo mode: task simulated successfully"),
                "Demo mode complete", false, "");
        }
        return new ActionDecision("SCREENSHOT",
            Map.of("label", "Current state observation"),
            "Capturing current state (demo mode)", false, "");
    }

    private String deterministic_synthesizeResult(SynthesisRequest request) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Task Summary\n\n");
        sb.append("Completed execution for: ").append(request.userRequest()).append("\n\n");
        if (!request.visitedUrls().isEmpty()) {
            sb.append("### Visited Sources\n");
            for (String url : request.visitedUrls()) {
                if (url != null && !url.isBlank()) {
                    sb.append("- ").append(url).append("\n");
                }
            }
            sb.append("\n");
        }
        if (!request.textSnippets().isEmpty()) {
            sb.append("### Key Findings\n");
            for (String snippet : request.textSnippets()) {
                if (snippet != null && !snippet.isBlank()) {
                    sb.append("- ").append(truncate(snippet, 300)).append("\n");
                }
            }
        }
        return sb.toString();
    }

    // ─── Accessors for testing ──────────────────────────────────────────────

    public GeminiKeyPool getKeyPool() { return keyPool; }
    public GeminiThrottle getThrottle() { return throttle; }
}
