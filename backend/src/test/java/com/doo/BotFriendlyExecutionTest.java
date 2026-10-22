package com.doo;

import com.doo.domain.*;
import com.doo.repository.*;
import com.doo.service.RoutePreferenceService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

@SpringBootTest(properties = {
    "doo.ai.demo-mode=true",
    "doo.ai.gemini.api-key="
})
@ActiveProfiles("h2")
@DisplayName("Bot-Friendly Execution Tests")
class BotFriendlyExecutionTest {

    @Autowired private TaskRepository taskRepository;
    @Autowired private TaskResultRepository taskResultRepository;
    @Autowired private TaskExecutorService executorService;
    @MockBean  private BrowserWorkerClient browserWorkerClient;
    @MockBean  private RoutePreferenceService routePreferenceService;

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
    @DisplayName("Per-domain cooldown tracking correctly tracks remaining time")
    void testDomainCooldownTracking() {
        String testDomain = "test-cooldown-domain.org";
        assertThat(TaskExecutorService.isDomainInCooldown(testDomain)).isFalse();

        TaskExecutorService.setDomainCooldown(testDomain, Duration.ofMinutes(15));
        assertThat(TaskExecutorService.isDomainInCooldown(testDomain)).isTrue();
        assertThat(TaskExecutorService.getDomainCooldownRemaining(testDomain).toMinutes()).isGreaterThanOrEqualTo(14);

        // Setting zero or past clears/expires
        TaskExecutorService.setDomainCooldown(testDomain, Duration.ofSeconds(-1));
        assertThat(TaskExecutorService.isDomainInCooldown(testDomain)).isFalse();
    }

    @Test
    @DisplayName("Task immediately fails politely if target domain is currently in cooldown")
    void testTaskStopsWhenDomainInCooldown() throws Exception {
        String coolDomain = "blocked-domain.com";
        TaskExecutorService.setDomainCooldown(coolDomain, Duration.ofMinutes(15));

        Task task = createTask("research facts on https://" + coolDomain + "/page");
        executorService.executeTask(task.getId());

        Task result = waitForTerminal(task.getId());
        assertThat(result.getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(result.getFailureReason()).contains("polite cooldown");

        // Clean up cooldown
        TaskExecutorService.setDomainCooldown(coolDomain, Duration.ofSeconds(-1));
    }

    @Test
    @DisplayName("Task stops immediately and informs user when robots.txt disallows path")
    void testTaskStopsWhenRobotsTxtDisallows() throws Exception {
        String targetUrl = "https://restricted-site.org/private/data";
        given(routePreferenceService.evaluate(eq(targetUrl), any()))
            .willReturn(RoutePreferenceService.RoutePreferenceResult.builder()
                .canProceed(false)
                .routeType(RoutePreferenceService.RouteType.ROBOTS_DISALLOWED)
                .stopReason("Access disallowed by site's robots.txt: Disallow /private/")
                .build());

        Task task = createTask("extract content from " + targetUrl);
        executorService.executeTask(task.getId());

        Task result = waitForTerminal(task.getId());
        assertThat(result.getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(result.getFailureReason()).contains("Access disallowed by site's robots.txt");
    }

    @Test
    @DisplayName("Official alternative routes are reported in final synthesized result")
    void testOfficialAlternativeReportedInResult() throws Exception {
        String targetUrl = "https://en.wikipedia.org/wiki/Main_Page";
        given(routePreferenceService.evaluate(eq(targetUrl), any()))
            .willReturn(RoutePreferenceService.RoutePreferenceResult.builder()
                .canProceed(true)
                .routeType(RoutePreferenceService.RouteType.OFFICIAL_API)
                .officialRouteUrl("https://en.wikipedia.org/api/rest_v1/page/summary/Main_Page")
                .description("Official Wikipedia REST API")
                .build());

        given(browserWorkerClient.initSession(any(), any()))
            .willReturn(new SessionResult(true, "sess-wp", null));
        given(browserWorkerClient.initSession(any()))
            .willReturn(new SessionResult(true, "sess-wp", null));

        given(browserWorkerClient.executeAction(any(), any(), eq("NAVIGATE"), any()))
            .willReturn(new ActionResult(true, "Navigated", null, null,
                targetUrl, "Wikipedia", "Main page content with historical facts", null));

        given(browserWorkerClient.executeAction(any(), any(), eq("SCREENSHOT"), any()))
            .willReturn(new ActionResult(true, "Screenshot", null, "base64img",
                targetUrl, "Wikipedia", "Main page content with historical facts", null));

        Task task = createTask("research facts on " + targetUrl);
        executorService.executeTask(task.getId());

        Task result = waitForTerminal(task.getId());
        assertThat(result.getStatus()).isIn(TaskStatus.COMPLETED, TaskStatus.PARTIAL);

        TaskResult taskResult = taskResultRepository.findByTaskId(task.getId()).orElse(null);
        assertThat(taskResult).isNotNull();
        assertThat(taskResult.getSummary()).contains("Automation Transparency & Official Alternatives");
        assertThat(taskResult.getSummary()).contains("api/rest_v1");
    }
}
