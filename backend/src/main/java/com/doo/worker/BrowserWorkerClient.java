package com.doo.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * HTTP client for communicating with the Node.js Playwright browser-worker service.
 * All browser actions go through this client.
 */
@Slf4j
@Component
public class BrowserWorkerClient {

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final int timeoutSeconds;

    public BrowserWorkerClient(
        @Value("${doo.browser-worker.url:http://localhost:3001}") String workerUrl,
        @Value("${doo.browser-worker.secret:dev-secret-change-in-production}") String secret,
        @Value("${doo.browser-worker.timeout-seconds:120}") int timeoutSeconds,
        ObjectMapper objectMapper
    ) {
        this.timeoutSeconds = timeoutSeconds;
        this.objectMapper = objectMapper;
        this.webClient = WebClient.builder()
            .baseUrl(workerUrl)
            .defaultHeader("X-Worker-Secret", secret)
            .defaultHeader("Content-Type", "application/json")
            .codecs(c -> c.defaultCodecs().maxInMemorySize(10 * 1024 * 1024)) // 10MB for screenshots
            .build();
    }

    /**
     * Initialize a browser session for a task.
     */
    public SessionResult initSession(UUID taskId) {
        try {
            Map response = webClient.post()
                .uri("/session/init")
                .bodyValue(Map.of("taskId", taskId.toString()))
                .retrieve()
                .bodyToMono(Map.class)
                .timeout(Duration.ofSeconds(30))
                .block();
            return new SessionResult(true, (String) response.get("sessionId"), null);
        } catch (Exception e) {
            log.error("Failed to init browser session for task {}: {}", taskId, e.getMessage());
            return new SessionResult(false, null, e.getMessage());
        }
    }

    /**
     * Execute a single browser action.
     */
    public ActionResult executeAction(UUID taskId, String sessionId, String actionType, Map<String, Object> args) {
        try {
            Map<String, Object> body = Map.of(
                "taskId", taskId.toString(),
                "sessionId", sessionId,
                "actionType", actionType,
                "args", args != null ? args : Map.of()
            );

            Map response = webClient.post()
                .uri("/action/execute")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Map.class)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .block();

            if (response == null) return ActionResult.failure("Empty response from browser worker");

            boolean success = Boolean.TRUE.equals(response.get("success"));
            String result = (String) response.get("result");
            String error = (String) response.get("error");
            String screenshot = (String) response.get("screenshot"); // base64 if captured
            String currentUrl = (String) response.get("currentUrl");
            String pageTitle = (String) response.get("pageTitle");
            String pageText = (String) response.get("pageText");
            String accessibilityTree = (String) response.get("accessibilityTree");

            return new ActionResult(success, result, error, screenshot, currentUrl, pageTitle, pageText, accessibilityTree);
        } catch (WebClientResponseException e) {
            log.error("Browser worker HTTP error {}: {}", e.getStatusCode(), e.getMessage());
            return ActionResult.failure("Browser worker error: " + e.getStatusCode() + " " + e.getResponseBodyAsString());
        } catch (Exception e) {
            log.error("Browser worker call failed for task {}: {}", taskId, e.getMessage());
            return ActionResult.failure("Browser worker unreachable: " + e.getMessage());
        }
    }

    /**
     * Get current browser state (URL, title, content).
     */
    public BrowserState getState(UUID taskId, String sessionId) {
        try {
            Map response = webClient.post()
                .uri("/session/state")
                .bodyValue(Map.of("taskId", taskId.toString(), "sessionId", sessionId))
                .retrieve()
                .bodyToMono(Map.class)
                .timeout(Duration.ofSeconds(15))
                .block();

            if (response == null) return BrowserState.empty();

            return new BrowserState(
                (String) response.get("currentUrl"),
                (String) response.get("pageTitle"),
                (String) response.get("pageText"),
                (String) response.get("accessibilityTree"),
                (String) response.get("screenshot")
            );
        } catch (Exception e) {
            log.warn("Could not get browser state for task {}: {}", taskId, e.getMessage());
            return BrowserState.empty();
        }
    }

    /**
     * Close a browser session and free resources.
     */
    public void closeSession(UUID taskId, String sessionId) {
        try {
            webClient.post()
                .uri("/session/close")
                .bodyValue(Map.of("taskId", taskId.toString(), "sessionId", sessionId))
                .retrieve()
                .bodyToMono(Void.class)
                .timeout(Duration.ofSeconds(10))
                .block();
            log.debug("Closed browser session {} for task {}", sessionId, taskId);
        } catch (Exception e) {
            log.warn("Failed to close browser session for task {}: {}", taskId, e.getMessage());
        }
    }

    /**
     * Cancel all pending operations for a task (idempotent).
     */
    public void cancelTask(UUID taskId) {
        try {
            webClient.post()
                .uri("/task/cancel")
                .bodyValue(Map.of("taskId", taskId.toString()))
                .retrieve()
                .bodyToMono(Void.class)
                .timeout(Duration.ofSeconds(10))
                .block();
        } catch (Exception e) {
            log.warn("Could not cancel task in browser worker {}: {}", taskId, e.getMessage());
        }
    }

    /**
     * Get a structured observation from the browser worker.
     */
    public Map<String, Object> observe(UUID taskId, boolean includeScreenshot) {
        try {
            Map response = webClient.post()
                .uri("/session/observe")
                .bodyValue(Map.of("taskId", taskId.toString(), "includeScreenshot", includeScreenshot))
                .retrieve()
                .bodyToMono(Map.class)
                .timeout(Duration.ofSeconds(15))
                .block();
            return response != null ? response : Map.of();
        } catch (Exception e) {
            log.warn("Could not get observation for task {}: {}", taskId, e.getMessage());
            return Map.of();
        }
    }

    /**
     * Check if browser worker is healthy.
     */
    public boolean isHealthy() {
        try {
            webClient.get()
                .uri("/health")
                .retrieve()
                .bodyToMono(Map.class)
                .timeout(Duration.ofSeconds(5))
                .block();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public record SessionResult(boolean success, String sessionId, String error) {}

    public record ActionResult(
        boolean success,
        String result,
        String error,
        String screenshot,
        String currentUrl,
        String pageTitle,
        String pageText,
        String accessibilityTree
    ) {
        public static ActionResult failure(String error) {
            return new ActionResult(false, null, error, null, null, null, null, null);
        }
    }

    public record BrowserState(
        String currentUrl,
        String pageTitle,
        String pageText,
        String accessibilityTree,
        String screenshot
    ) {
        public static BrowserState empty() {
            return new BrowserState(null, null, null, null, null);
        }
    }
}
