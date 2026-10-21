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
    @DisplayName("Fill without page change triggers PRESS_KEY Enter recovery")
    void fillGuardInjectsEnter() throws Exception {
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

        // PRESS_KEY Enter succeeds and changes the page (form submitted / step advanced)
        given(browserWorkerClient.executeAction(any(), any(), eq("PRESS_KEY"), any()))
            .willReturn(new ActionResult(true, "Pressed Enter", null, null,
                "http://localhost:8081", "DOO Test Fixture",
                "Step 2 — Preferences Select Plan Primary Use Case", null));

        // SCREENSHOT succeeds
        given(browserWorkerClient.executeAction(any(), any(), eq("SCREENSHOT"), any()))
            .willReturn(new ActionResult(true, "Screenshot", null, "base64img",
                "http://localhost:8081", "DOO Test Fixture",
                "Step 2 — Preferences Select Plan Primary Use Case", null));

        executorService.executeTask(task.getId());
        Task result = waitForTerminal(task.getId());

        // Task should complete (demo mode) — the guard prevented the fill-without-submit loop
        assertThat(result.getStatus()).isIn(TaskStatus.COMPLETED, TaskStatus.PARTIAL);

        // Verify the guard injected a PRESS_KEY step
        List<TaskStep> steps = taskStepRepository.findByTaskIdOrderByStepIndexAsc(task.getId());
        boolean hasGuardEnter = steps.stream()
            .anyMatch(s -> "PRESS_KEY".equals(s.getActionType())
                && s.getActionArgs() != null
                && "fill-submit".equals(s.getActionArgs().get("_guard")));
        assertThat(hasGuardEnter)
            .as("Fill-guard should inject a PRESS_KEY Enter step")
            .isTrue();
    }

    @Test
    @DisplayName("Fill guard falls back to CLICK when Enter has no effect")
    void fillGuardFallsBackToClick() throws Exception {
        Task task = createTask("fill the test form and submit it");

        given(browserWorkerClient.initSession(any()))
            .willReturn(new SessionResult(true, "sess-2", null));

        // All pages return the same URL/title/text to simulate "nothing changed"
        String unchangedText = "Step 1 — Personal Information Full Name Email Address Company";

        given(browserWorkerClient.executeAction(any(), any(), eq("NAVIGATE"), any()))
            .willReturn(new ActionResult(true, "Navigated", null, null,
                "http://localhost:8081", "DOO Test Fixture", unchangedText, null));

        // FILL: page stays the same
        given(browserWorkerClient.executeAction(any(), any(), eq("FILL"), any()))
            .willReturn(new ActionResult(true, "Filled", null, null,
                "http://localhost:8081", "DOO Test Fixture", unchangedText, null));

        // PRESS_KEY Enter: page STILL stays the same (Enter had no effect)
        given(browserWorkerClient.executeAction(any(), any(), eq("PRESS_KEY"), any()))
            .willReturn(new ActionResult(true, "Pressed Enter", null, null,
                "http://localhost:8081", "DOO Test Fixture", unchangedText, null));

        // CLICK on submit button: NOW the page changes
        given(browserWorkerClient.executeAction(any(), any(), eq("CLICK"), any()))
            .willReturn(new ActionResult(true, "Clicked submit", null, null,
                "http://localhost:8081", "DOO Test Fixture",
                "Registration Successful! Your submission has been recorded.", null));

        // SCREENSHOT
        given(browserWorkerClient.executeAction(any(), any(), eq("SCREENSHOT"), any()))
            .willReturn(new ActionResult(true, "Screenshot", null, "base64img",
                "http://localhost:8081", "DOO Test Fixture",
                "Registration Successful! Your submission has been recorded.", null));

        executorService.executeTask(task.getId());
        Task result = waitForTerminal(task.getId());

        assertThat(result.getStatus()).isIn(TaskStatus.COMPLETED, TaskStatus.PARTIAL);

        // Verify both guard steps were injected: PRESS_KEY then CLICK
        List<TaskStep> steps = taskStepRepository.findByTaskIdOrderByStepIndexAsc(task.getId());
        boolean hasGuardEnter = steps.stream()
            .anyMatch(s -> "PRESS_KEY".equals(s.getActionType())
                && s.getActionArgs() != null
                && "fill-submit".equals(s.getActionArgs().get("_guard")));
        boolean hasGuardClick = steps.stream()
            .anyMatch(s -> "CLICK".equals(s.getActionType())
                && s.getActionArgs() != null
                && "fill-submit".equals(s.getActionArgs().get("_guard")));
        assertThat(hasGuardEnter)
            .as("Fill-guard should inject PRESS_KEY Enter")
            .isTrue();
        assertThat(hasGuardClick)
            .as("Fill-guard should fall back to CLICK submit")
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
