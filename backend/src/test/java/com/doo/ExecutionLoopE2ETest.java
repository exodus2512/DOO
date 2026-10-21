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
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

/**
 * End-to-end tests that exercise the full TaskExecutorService loop
 * with the deterministic demo AI provider and a mocked browser worker.
 */
@SpringBootTest(properties = {
    "doo.ai.demo-mode=true",
    "doo.ai.gemini.api-key="
})
@ActiveProfiles("h2")
@DisplayName("Execution Loop E2E Tests")
class ExecutionLoopE2ETest {

    @Autowired
    private TaskRepository taskRepository;
    @Autowired
    private TaskStepRepository taskStepRepository;
    @Autowired
    private TaskResultRepository taskResultRepository;
    @Autowired
    private TaskExecutorService executorService;

    @MockBean
    private BrowserWorkerClient browserWorkerClient;

    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(200);

    private Task createTestTask(String request) {
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
            Thread.sleep(POLL_INTERVAL.toMillis());
        }
        return taskRepository.findById(taskId).orElseThrow();
    }

    @Test
    @DisplayName("Demo mode web research task completes through full loop")
    void demoWebResearchCompletes() throws Exception {
        Task task = createTestTask("Research the top AI agent frameworks");

        given(browserWorkerClient.initSession(any())).willReturn(
            new SessionResult(true, "test-session", null));
        given(browserWorkerClient.executeAction(any(), any(), eq("NAVIGATE"), any())).willReturn(
            new ActionResult(true, "Navigated", null, null,
                "https://en.wikipedia.org/wiki/Main_Page", "Wikipedia",
                "Wikipedia main page content that is definitely long enough to store as a snippet for evidence collection purposes", null));
        given(browserWorkerClient.executeAction(any(), any(), eq("SCREENSHOT"), any())).willReturn(
            new ActionResult(true, "Screenshot captured", null, "base64screenshot",
                "https://en.wikipedia.org/wiki/Main_Page", "Wikipedia",
                "Wikipedia main page content that is definitely long enough to store as a snippet for evidence collection purposes", null));

        executorService.executeTask(task.getId());
        Task result = waitForTerminal(task.getId());

        assertThat(result.getStatus()).isIn(TaskStatus.COMPLETED, TaskStatus.PARTIAL);
        assertThat(result.getWorkflowType()).isEqualTo(WorkflowType.WEB_RESEARCH);
        assertThat(result.getPlan()).isNotBlank();

        TaskResult taskResult = taskResultRepository.findByTaskId(task.getId()).orElse(null);
        assertThat(taskResult).isNotNull();
        assertThat(taskResult.getSummary()).isNotBlank();
        assertThat(taskResult.getVerificationPassed()).isNotNull();

        List<TaskStep> steps = taskStepRepository.findByTaskIdOrderByStepIndexAsc(task.getId());
        assertThat(steps).isNotEmpty();
        assertThat(steps.get(0).getActionType()).isEqualTo("NAVIGATE");
    }

    @Test
    @DisplayName("Browser init failure results in FAILED task")
    void browserInitFailureFails() throws Exception {
        Task task = createTestTask("Research something about quantum computing");

        given(browserWorkerClient.initSession(any())).willReturn(
            new SessionResult(false, null, "Browser launch failed"));

        executorService.executeTask(task.getId());
        Task result = waitForTerminal(task.getId());

        assertThat(result.getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(result.getFailureReason()).contains("Could not launch browser");
    }

    @Test
    @DisplayName("Consecutive action failures result in FAILED task")
    void consecutiveFailuresFail() throws Exception {
        Task task = createTestTask("Research something interesting here");

        given(browserWorkerClient.initSession(any())).willReturn(
            new SessionResult(true, "test-session", null));
        given(browserWorkerClient.executeAction(any(), any(), any(), any())).willReturn(
            ActionResult.failure("Network error"));
        given(browserWorkerClient.getState(any(), any())).willReturn(BrowserState.empty());

        executorService.executeTask(task.getId());
        Task result = waitForTerminal(task.getId());

        assertThat(result.getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(result.getFailureReason()).isNotBlank();
    }

    @Test
    @DisplayName("Observation epoch is recorded in step args")
    void observationEpochTracking() throws Exception {
        Task task = createTestTask("Research the latest AI tools and frameworks");

        given(browserWorkerClient.initSession(any())).willReturn(
            new SessionResult(true, "test-session", null));
        given(browserWorkerClient.executeAction(any(), any(), eq("NAVIGATE"), any())).willReturn(
            new ActionResult(true, "Navigated", null, null,
                "https://en.wikipedia.org/wiki/Main_Page", "Wikipedia",
                "Wikipedia main page with lots of interesting content about everything and many topics", null));
        given(browserWorkerClient.executeAction(any(), any(), eq("SCREENSHOT"), any())).willReturn(
            new ActionResult(true, "Screenshot", null, "base64img",
                "https://en.wikipedia.org/wiki/Main_Page", "Wikipedia",
                "Wikipedia main page with lots of interesting content about everything and many topics", null));

        executorService.executeTask(task.getId());
        waitForTerminal(task.getId());

        List<TaskStep> steps = taskStepRepository.findByTaskIdOrderByStepIndexAsc(task.getId());
        assertThat(steps).isNotEmpty();

        TaskStep firstStep = steps.get(0);
        assertThat(firstStep.getActionArgs()).containsKey("_observationEpoch");
        assertThat(firstStep.getActionArgs().get("_observationEpoch")).isEqualTo(0);
    }

    @Test
    @DisplayName("Task cancellation stops execution loop")
    void cancellationStopsExecution() throws Exception {
        Task task = createTestTask("Research something very long running please");

        given(browserWorkerClient.initSession(any())).willReturn(
            new SessionResult(true, "test-session", null));
        given(browserWorkerClient.executeAction(any(), any(), any(), any())).willAnswer(invocation -> {
            executorService.cancelTask(task.getId());
            return new ActionResult(true, "ok", null, null, "https://example.com", "Ex", "short content", null);
        });

        executorService.executeTask(task.getId());
        Task result = waitForTerminal(task.getId());

        assertThat(result.getStatus()).isEqualTo(TaskStatus.CANCELLED);
    }
}
