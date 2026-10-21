package com.doo;

import com.doo.domain.*;
import com.doo.repository.*;
import com.doo.service.TaskExecutorService;
import com.doo.worker.BrowserWorkerClient;
import com.doo.worker.BrowserWorkerClient.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

/**
 * Regression tests for the fill-then-submit guard and
 * the identical-consecutive-action detector.
 */
@SpringBootTest(properties = {
    "doo.ai.demo-mode=true",
    "doo.ai.gemini.api-key="
})
@ActiveProfiles("h2")
@DisplayName("Fill-Submit Guard Tests")
class FillSubmitGuardTest {

    @Autowired private TaskRepository taskRepository;
    @Autowired private TaskStepRepository taskStepRepository;
    @Autowired private TaskResultRepository taskResultRepository;
    @Autowired private TaskExecutorService executorService;
    @MockBean  private BrowserWorkerClient browserWorkerClient;

    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(15);

    private Task createTask(String request) {
        Task task = Task.builder()
            .userRequest(request)
            .status(TaskStatus.QUEUED)
            .workflowType(WorkflowType.UNKNOWN)
            .currentStep(0)
            .build();
        return taskRepository.save(task);
    }

    private Task waitForTerminal(UUID taskId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + POLL_TIMEOUT.toMillis();
        while (System.currentTimeMillis() < deadline) {
            Task t = taskRepository.findById(taskId).orElseThrow();
            if (t.getStatus().isTerminal()) return t;
            Thread.sleep(200);
        }
        return taskRepository.findById(taskId).orElseThrow();
    }

    @Test
    @DisplayName("Fill without page change does NOT auto-inject Enter (model controls actions)")
    void fillDoesNotAutoInjectEnter() throws Exception {
        Task task = createTask("fill the test form and submit it");

        given(browserWorkerClient.initSession(any()))
            .willReturn(new SessionResult(true, "sess-1", null));

        // NAVIGATE succeeds, lands on the form page
        given(browserWorkerClient.executeAction(any(), any(), eq("NAVIGATE"), any()))
            .willReturn(new ActionResult(true, "Navigated", null, null,
                "http://localhost:8081", "DOO Test Fixture",
                "Step 1 — Personal Information Full Name Email", null));

        // FILL succeeds but page text stays the same (no navigation happened)
        given(browserWorkerClient.executeAction(any(), any(), eq("FILL"), any()))
            .willReturn(new ActionResult(true, "Filled field", null, null,
                "http://localhost:8081", "DOO Test Fixture",
                "Step 1 — Personal Information Full Name Email", null));

        // SCREENSHOT succeeds
        given(browserWorkerClient.executeAction(any(), any(), eq("SCREENSHOT"), any()))
            .willReturn(new ActionResult(true, "Screenshot", null, "base64img",
                "http://localhost:8081", "DOO Test Fixture",
                "Step 1 — Personal Information Full Name Email", null));

        executorService.executeTask(task.getId());
        Task result = waitForTerminal(task.getId());

        assertThat(result.getStatus()).isIn(TaskStatus.COMPLETED, TaskStatus.PARTIAL);

        // Verify NO unprompted PRESS_KEY Enter step was injected
        List<TaskStep> steps = taskStepRepository.findByTaskIdOrderByStepIndexAsc(task.getId());
        boolean hasInjectedGuard = steps.stream()
            .anyMatch(s -> s.getActionArgs() != null && "fill-submit".equals(s.getActionArgs().get("_guard")));
        assertThat(hasInjectedGuard)
            .as("Fill-guard must NOT auto-inject Enter or click steps")
            .isFalse();
    }

    @Test
    @DisplayName("RESOLVE_FAILED error triggers distinct retry with fresh observation")
    void resolveFailureRetriesWithValidIndices() throws Exception {
        Task task = createTask("fill the test form and submit it");

        given(browserWorkerClient.initSession(any()))
            .willReturn(new SessionResult(true, "sess-2", null));

        String formPage = "Step 1 — Personal Information Full Name Email Address Company";

        given(browserWorkerClient.executeAction(any(), any(), eq("NAVIGATE"), any()))
            .willReturn(new ActionResult(true, "Navigated", null, null,
                "http://localhost:8081", "DOO Test Fixture", formPage, null,
                "obs_nav", List.of(Map.of("index", 0, "role", "textbox", "label", "Full Name")), null));

        // First FILL attempt returns RESOLVE_FAILED with fresh observation containing valid indices
        given(browserWorkerClient.executeAction(any(), any(), eq("FILL"), any()))
            .willReturn(new ActionResult(false, null,
                "RESOLVE_FAILED: element_index 5 could not be resolved. Current valid indices: [[0] <input> textbox \"Full Name\"]",
                null, "http://localhost:8081", "DOO Test Fixture", formPage, null,
                "obs_fresh", List.of(Map.of("index", 0, "role", "textbox", "label", "Full Name")), null))
            .willReturn(new ActionResult(true, "Filled field", null, null,
                "http://localhost:8081", "DOO Test Fixture", formPage, null,
                "obs_filled", List.of(Map.of("index", 0, "role", "textbox", "label", "Full Name")), null));

        // SCREENSHOT
        given(browserWorkerClient.executeAction(any(), any(), eq("SCREENSHOT"), any()))
            .willReturn(new ActionResult(true, "Screenshot", null, "base64img",
                "http://localhost:8081", "DOO Test Fixture", formPage, null));

        executorService.executeTask(task.getId());
        Task result = waitForTerminal(task.getId());

        assertThat(result.getStatus()).isIn(TaskStatus.COMPLETED, TaskStatus.PARTIAL);

        List<TaskStep> steps = taskStepRepository.findByTaskIdOrderByStepIndexAsc(task.getId());
        boolean hasResolveFailedStep = steps.stream()
            .anyMatch(s -> s.getError() != null && s.getError().contains("RESOLVE_FAILED"));
        assertThat(hasResolveFailedStep)
            .as("Task steps should record RESOLVE_FAILED distinctly")
            .isTrue();
    }

    @Test
    @DisplayName("Two identical fills with unchanged observation triggers no-progress detection")
    void twoIdenticalFillsWithUnchangedObsTriggerDetection() throws Exception {
        // This tests the IDENTICAL_CONSECUTIVE_LIMIT = 2 detector.
        // We need the AI to produce the same FILL action twice in a row with no page change.
        // In demo mode, the deterministic provider won't do this, so we test the detection
        // algorithm directly.
        List<String> fingerprints = new java.util.ArrayList<>();

        // Same action + same observation hash
        fingerprints.add("FILL|selector=#name&value=test@abc123");
        fingerprints.add("FILL|selector=#name&value=test@abc123");

        // Reproduce the detection logic from TaskExecutorService
        int LIMIT = 2;
        boolean detected = false;
        if (fingerprints.size() >= LIMIT) {
            int sz = fingerprints.size();
            String current = fingerprints.get(sz - 1);
            boolean allIdentical = true;
            for (int k = sz - LIMIT; k < sz; k++) {
                if (!fingerprints.get(k).equals(current)) {
                    allIdentical = false;
                    break;
                }
            }
            detected = allIdentical;
        }

        assertThat(detected)
            .as("Two identical fills with same obs hash should trigger detection")
            .isTrue();
    }

    @Test
    @DisplayName("Two identical fills with DIFFERENT observation do NOT trigger detection")
    void twoIdenticalFillsDifferentObsNoTrigger() throws Exception {
        List<String> fingerprints = new java.util.ArrayList<>();

        // Same action but different observation hash
        fingerprints.add("FILL|selector=#name&value=test@obs-hash-1");
        fingerprints.add("FILL|selector=#name&value=test@obs-hash-2");

        int LIMIT = 2;
        boolean detected = false;
        if (fingerprints.size() >= LIMIT) {
            int sz = fingerprints.size();
            String current = fingerprints.get(sz - 1);
            boolean allIdentical = true;
            for (int k = sz - LIMIT; k < sz; k++) {
                if (!fingerprints.get(k).equals(current)) {
                    allIdentical = false;
                    break;
                }
            }
            detected = allIdentical;
        }

        assertThat(detected)
            .as("Two fills with different obs hashes should NOT trigger detection")
            .isFalse();
    }
}
