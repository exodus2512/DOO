package com.doo.ai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.*;

/**
 * Google Gemini implementation of AiProvider.
 * Uses gemini-1.5-flash by default (cost-effective for research tasks).
 */
@Slf4j
@Component
@Primary
public class GeminiAiProvider implements AiProvider {

    private static final String GEMINI_BASE_URL = "https://generativelanguage.googleapis.com/v1beta";

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String model;
    private final boolean demoMode;

    public GeminiAiProvider(
        @Value("${doo.ai.gemini.api-key:}") String apiKey,
        @Value("${doo.ai.gemini.model:gemini-1.5-flash}") String model,
        @Value("${doo.ai.demo-mode:false}") boolean demoMode,
        ObjectMapper objectMapper
    ) {
        this.apiKey = apiKey;
        this.model = model;
        this.demoMode = demoMode;
        this.objectMapper = objectMapper;
        this.webClient = WebClient.builder()
            .baseUrl(GEMINI_BASE_URL)
            .defaultHeader("Content-Type", "application/json")
            .build();
    }

    private boolean isAvailable() {
        return !demoMode && apiKey != null && !apiKey.isBlank();
    }

    @Override
    public WorkflowClassification classifyWorkflow(String userRequest) {
        if (!isAvailable()) {
            return deterministic_classifyWorkflow(userRequest);
        }
        String prompt = """
            You are DOO, an AI agent. Classify this user request into exactly one workflow type.
            
            User request: "%s"
            
            Respond in JSON format ONLY (no markdown, no explanation outside JSON):
            {
              "workflowType": "WEB_RESEARCH|PRODUCT_COMPARISON|UI_INTERACTION|UNKNOWN",
              "rationale": "one sentence explanation",
              "successCriteria": "specific, testable conditions for completion"
            }
            """.formatted(userRequest);

        try {
            String response = callGemini(prompt);
            JsonNode json = objectMapper.readTree(extractJson(response));
            return new WorkflowClassification(
                json.path("workflowType").asText("UNKNOWN"),
                json.path("rationale").asText(""),
                json.path("successCriteria").asText("")
            );
        } catch (Exception e) {
            log.warn("Failed to classify workflow via Gemini: {}", e.getMessage());
            return deterministic_classifyWorkflow(userRequest);
        }
    }

    @Override
    public TaskPlan generatePlan(String userRequest, String successCriteria, WorkflowContext context) {
        if (!isAvailable()) {
            return deterministic_generatePlan(userRequest, context.workflowType());
        }
        String prompt = """
            You are DOO, an AI browser agent. Create a step-by-step execution plan.
            
            User request: "%s"
            Workflow type: %s
            Success criteria: %s
            
            Rules:
            - Each step should be a concrete browser action or observation
            - Maximum 15 steps
            - For WEB_RESEARCH: start with search, visit pages, extract info, synthesize
            - For PRODUCT_COMPARISON: visit each product URL, extract specs/price, compare
            - For UI_INTERACTION: navigate to test form, fill fields, submit, verify
            - Do NOT include steps that require login or payment
            
            Respond in JSON format ONLY:
            {
              "steps": ["step 1 description", "step 2 description", ...],
              "successCriteria": "refined testable criteria"
            }
            """.formatted(userRequest, context.workflowType(), successCriteria);

        try {
            String response = callGemini(prompt);
            JsonNode json = objectMapper.readTree(extractJson(response));
            List<String> steps = new ArrayList<>();
            json.path("steps").forEach(n -> steps.add(n.asText()));
            return new TaskPlan(steps, json.path("successCriteria").asText(successCriteria));
        } catch (Exception e) {
            log.warn("Failed to generate plan via Gemini: {}", e.getMessage());
            return deterministic_generatePlan(userRequest, context.workflowType());
        }
    }

    @Override
    public ActionDecision selectNextAction(ActionSelectionRequest request) {
        if (!isAvailable()) {
            return deterministic_selectAction(request);
        }
        String prompt = """
            You are DOO, an AI browser agent selecting the next browser action.
            
            Task: "%s"
            Current plan step %d: %s
            Current URL: %s
            Page title: %s
            Page content (truncated): %s
            Recent actions: %s
            Allowed action types: %s
            
            Select ONE action from the allowed types. Respond in JSON format ONLY:
            {
              "actionType": "NAVIGATE|CLICK|FILL|EXTRACT_TEXT|SCREENSHOT|SCROLL|WAIT|DONE",
              "args": { ... action-specific args ... },
              "rationale": "why this action",
              "requiresApproval": false,
              "approvalReason": ""
            }
            
            Action args by type:
            - NAVIGATE: {"url": "https://..."}
            - CLICK: {"selector": "css or xpath", "description": "what element"}
            - FILL: {"selector": "...", "value": "text to enter", "description": "field name"}
            - EXTRACT_TEXT: {"selector": "...", "label": "what we're extracting"}
            - SCREENSHOT: {"label": "description of what we're capturing"}
            - SCROLL: {"direction": "down|up", "amount": 500}
            - WAIT: {"milliseconds": 1000}
            - DONE: {"reason": "why we're done"}
            
            IMPORTANT: Only navigate to public URLs. Never navigate to localhost, file://, or private IPs.
            Never fill password fields. Never click purchase/checkout/buy/subscribe buttons.
            """.formatted(
                request.userRequest(),
                request.currentStep(),
                request.planSteps().isEmpty() ? "N/A" : request.planSteps().get(
                    Math.min(request.currentStep(), request.planSteps().size() - 1)),
                request.currentUrl(),
                request.pageTitle(),
                truncate(request.pageText(), 2000),
                String.join(", ", request.recentActions().stream().limit(5).toList()),
                String.join(", ", request.allowedActionTypes())
            );

        try {
            String response = callGemini(prompt);
            JsonNode json = objectMapper.readTree(extractJson(response));
            Map<String, Object> args = objectMapper.convertValue(
                json.path("args"), new TypeReference<Map<String, Object>>() {});
            return new ActionDecision(
                json.path("actionType").asText("SCREENSHOT"),
                args != null ? args : Map.of(),
                json.path("rationale").asText(""),
                json.path("requiresApproval").asBoolean(false),
                json.path("approvalReason").asText("")
            );
        } catch (Exception e) {
            log.warn("Failed to select next action via Gemini: {}", e.getMessage());
            return deterministic_selectAction(request);
        }
    }

    @Override
    public VerificationResult verify(VerificationRequest request) {
        if (!isAvailable()) {
            return new VerificationResult(true, "Demo mode: verification simulated", 0.7);
        }
        String prompt = """
            You are DOO's verification engine. Determine if the task was completed successfully.
            
            User request: "%s"
            Success criteria: "%s"
            Workflow type: %s
            Final URL: %s
            Visited URLs: %s
            Synthesized result (truncated): %s
            
            Did the execution meet the success criteria? Respond in JSON format ONLY:
            {
              "passed": true|false,
              "notes": "specific explanation of what was/wasn't achieved",
              "confidence": 0.0-1.0
            }
            """.formatted(
                request.userRequest(),
                request.successCriteria(),
                request.workflowType(),
                request.finalUrl(),
                String.join(", ", request.visitedUrls()),
                truncate(request.synthesizedResult(), 3000)
            );

        try {
            String response = callGemini(prompt);
            JsonNode json = objectMapper.readTree(extractJson(response));
            return new VerificationResult(
                json.path("passed").asBoolean(false),
                json.path("notes").asText(""),
                json.path("confidence").asDouble(0.5)
            );
        } catch (Exception e) {
            log.warn("Failed to parse verification: {}", e.getMessage());
            return new VerificationResult(false, "Verification could not be completed: " + e.getMessage(), 0.0);
        }
    }

    @Override
    public String synthesizeResult(SynthesisRequest request) {
        if (!isAvailable()) {
            return "Demo mode: This is a simulated result. No AI provider is configured. " +
                   "Visited URLs: " + String.join(", ", request.visitedUrls());
        }
        String prompt = """
            You are DOO. Synthesize a clear, structured answer based on the evidence collected.
            
            User request: "%s"
            Workflow: %s
            Visited URLs: %s
            Evidence collected: %s
            
            Write a clear, well-structured answer. For research tasks, use markdown with sections.
            Include source references. Be honest about gaps in information.
            Do NOT invent facts not present in the evidence.
            """.formatted(
                request.userRequest(),
                request.workflowType(),
                String.join(", ", request.visitedUrls()),
                truncate(String.join("\n\n---\n\n", request.textSnippets()), 6000)
            );

        try {
            return callGemini(prompt);
        } catch (Exception e) {
            log.warn("Failed to synthesize result via Gemini: {}", e.getMessage());
            return deterministic_synthesizeResult(request);
        }
    }

    private String callGemini(String prompt) {
        Set<String> candidateModels = new LinkedHashSet<>();
        if (this.model != null && !this.model.isBlank()) {
            candidateModels.add(this.model);
        }
        candidateModels.add("gemini-3.5-flash");
        candidateModels.add("gemini-3.5-flash-lite");
        candidateModels.add("gemini-3.6-flash");
        candidateModels.add("gemini-flash-latest");
        Exception lastException = null;

        for (String targetModel : candidateModels) {
            for (int attempt = 1; attempt <= 3; attempt++) {
                try {
                    Map<String, Object> body = Map.of(
                        "contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))),
                        "generationConfig", Map.of(
                            "temperature", 0.2,
                            "maxOutputTokens", 2048
                        )
                    );

                    Map response = webClient.post()
                        .uri("/models/" + targetModel + ":generateContent?key=" + apiKey)
                        .bodyValue(body)
                        .retrieve()
                        .bodyToMono(Map.class)
                        .timeout(java.time.Duration.ofSeconds(15))
                        .block();

                    if (response == null) continue;

                    List candidates = (List) response.get("candidates");
                    if (candidates == null || candidates.isEmpty()) continue;

                    Map candidate = (Map) candidates.get(0);
                    Map content = (Map) candidate.get("content");
                    if (content == null) continue;

                    List parts = (List) content.get("parts");
                    if (parts == null || parts.isEmpty()) continue;

                    return (String) ((Map) parts.get(0)).get("text");
                } catch (Exception e) {
                    lastException = e;
                    log.warn("Gemini call failed (model={}, attempt={}/3): {}", targetModel, attempt, e.getMessage());
                    try {
                        Thread.sleep(attempt * 1200L); // 1.2s, 2.4s backoff
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Interrupted during Gemini call", ie);
                    }
                }
            }
        }

        log.error("All Gemini API attempts failed: {}", lastException != null ? lastException.getMessage() : "unknown");
        throw new RuntimeException("AI provider error: " + (lastException != null ? lastException.getMessage() : "All models unavailable"), lastException);
    }

    private String extractJson(String text) {
        if (text == null) return "{}";
        // Strip markdown code fences if present
        text = text.trim();
        if (text.startsWith("```")) {
            int start = text.indexOf('\n') + 1;
            int end = text.lastIndexOf("```");
            if (end > start) text = text.substring(start, end).trim();
        }
        // Find first { or [
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

    // ─── Deterministic fallbacks (demo/test mode) ──────────────────────────────

    private WorkflowClassification deterministic_classifyWorkflow(String request) {
        String lower = request.toLowerCase();
        if (lower.contains("research") || lower.contains("compare") || lower.contains("difference")) {
            return new WorkflowClassification("WEB_RESEARCH",
                "Request appears to be a research task",
                "Result contains comparison with source URLs");
        }
        if (lower.contains("product") || lower.contains("price") || lower.contains("buy")) {
            return new WorkflowClassification("PRODUCT_COMPARISON",
                "Request appears to be a product comparison",
                "Each product has extracted specs and price with traceable sources");
        }
        if (lower.contains("form") || lower.contains("fill") || lower.contains("test") || lower.contains("submit")) {
            return new WorkflowClassification("UI_INTERACTION",
                "Request appears to be a UI interaction task",
                "Form submitted and confirmation page verified");
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
                "Navigate to the first product URL",
                "Extract product name, price, and features",
                "Navigate to the second product URL",
                "Extract product name, price, and features",
                "Produce comparison table with sources"
            ), "All products have extracted data with traceable URLs");
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
        // Simple deterministic: if no URL yet, navigate to a safe starting point
        if (request.currentUrl() == null || request.currentUrl().isBlank()) {
            return new ActionDecision("NAVIGATE",
                Map.of("url", "https://en.wikipedia.org/wiki/Main_Page"),
                "Starting navigation (demo mode)", false, "");
        }
        // Otherwise take a screenshot and mark done
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
}
