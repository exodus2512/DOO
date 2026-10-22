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
    @Autowired private ApprovalRequestRepository approvalRepository;
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

    @Test
    @DisplayName("Cloudflare block page with 'Attention Required' pauses backend execution with BOT_BLOCK approval")
    void testBackendPausesOnCloudflareBlockPage() throws Exception {
        String targetUrl = "https://en.wikipedia.org/wiki/Main_Page";
        given(routePreferenceService.evaluate(any(), any()))
            .willReturn(RoutePreferenceService.RoutePreferenceResult.builder()
                .canProceed(true)
                .routeType(RoutePreferenceService.RouteType.BROWSER_ALLOWED)
                .build());

        given(browserWorkerClient.initSession(any(), any()))
            .willReturn(new SessionResult(true, "sess-cf", null));
        given(browserWorkerClient.initSession(any()))
            .willReturn(new SessionResult(true, "sess-cf", null));
        given(browserWorkerClient.unparkSession(any()))
            .willReturn(new ActionResult(true, "Unparked", null, null, targetUrl, "Title", "Text", null));

        // Simulate worker returning observation of the Cloudflare block page
        given(browserWorkerClient.executeAction(any(), any(), eq("NAVIGATE"), any()))
            .willReturn(new ActionResult(true, "Navigated", null, "base64screenshot",
                targetUrl, "Attention Required! | Cloudflare",
                "Sorry, you have been blocked. You are unable to access medium.com",
                null, "obs-cf", List.of(),
                List.of(Map.of("type", "bot_block", "reason", "Attention Required! | Cloudflare")),
                null, "BOT_BLOCK"));

        Task task = createTask("read article on " + targetUrl);
        executorService.executeTask(task.getId());

        // Verify that the backend pauses on it (enters WAITING_FOR_USER)
        long deadline = System.currentTimeMillis() + POLL_TIMEOUT.toMillis();
        Task pausedTask = null;
        while (System.currentTimeMillis() < deadline) {
            Task t = taskRepository.findById(task.getId()).orElseThrow();
            if (t.getStatus() == TaskStatus.WAITING_FOR_USER) {
                pausedTask = t;
                break;
            }
            Thread.sleep(200);
        }

        assertThat(pausedTask).isNotNull();
        assertThat(pausedTask.getStatus()).isEqualTo(TaskStatus.WAITING_FOR_USER);

        // Verify approval card of type BOT_BLOCK was created with live view / skip / stop options
        java.util.Optional<ApprovalRequest> approval = approvalRepository
            .findFirstByTaskIdAndProposedActionAndStatus(task.getId(), "BOT_BLOCK", "PENDING");
        assertThat(approval).isPresent();
        assertThat(approval.get().getPrompt()).contains("Bot block detected at " + targetUrl);
        assertThat(approval.get().getParameters()).isNotNull();
        assertThat(approval.get().getParameters().get("type")).isEqualTo("bot_block");

        // Verify domain entered cooldown immediately
        assertThat(TaskExecutorService.isDomainInCooldown("en.wikipedia.org")).isTrue();

        // Resolve approval by user choosing to stop
        ApprovalRequest req = approval.get();
        req.setStatus("APPROVED");
        req.setUserResponse("stop");
        approvalRepository.save(req);

        Task finalTask = waitForTerminal(task.getId());
        assertThat(finalTask.getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(finalTask.getFailureReason()).contains("BOT_BLOCK: user chose to stop");

        // Clean up cooldown
        TaskExecutorService.setDomainCooldown("en.wikipedia.org", Duration.ofSeconds(-1));
    }

    @Test
    @DisplayName("handleAuthGateResponse: non-JSON response treats as handoff without throwing exception")
    void testHandleAuthGateResponseNonJson() {
        Map<String, String> creds = new java.util.HashMap<>();
        Map<String, String> known = new java.util.HashMap<>();
        java.util.Set<String> handled = new java.util.HashSet<>();
        UUID taskId = UUID.randomUUID();

        // 1. Plain non-JSON text -> handoff
        executorService.handleAuthGateResponse("plain text response", "login.example.com",
            creds, known, handled, taskId);
        assertThat(handled).contains("login.example.com");
        assertThat(creds).isEmpty();
        assertThat(known).isEmpty();

        // 2. Unrecognized choice -> handoff
        executorService.handleAuthGateResponse("{\"choice\":\"unknown_sso\"}", "sso.example.com",
            creds, known, handled, taskId);
        assertThat(handled).contains("sso.example.com");
        assertThat(creds).isEmpty();
        assertThat(known).isEmpty();

        // 3. Password choice -> sets task credentials
        executorService.handleAuthGateResponse("{\"choice\":\"password\",\"username\":\"alice\",\"password\":\"secret\"}",
            "login.example.com", creds, known, handled, taskId);
        assertThat(creds.get("login.example.com")).isEqualTo("alice\nsecret");

        // 4. Email link choice -> sets known values
        executorService.handleAuthGateResponse("{\"choice\":\"email_link\",\"email\":\"alice@example.com\"}",
            "login.example.com", creds, known, handled, taskId);
        assertThat(known.get("email")).isEqualTo("alice@example.com");

        // 5. Phone choice -> sets known values
        executorService.handleAuthGateResponse("{\"choice\":\"phone\",\"phone\":\"+1234567890\"}",
            "login.example.com", creds, known, handled, taskId);
        assertThat(known.get("phone")).isEqualTo("+1234567890");
    }

    @Test
    @DisplayName("Repeated failed auth verification fails task after 2 attempts instead of looping third card")
    void testAuthGateMaxAttemptsFailsTask() throws Exception {
        String targetUrl = "https://secure-portal.org/login";
        String domain = "secure-portal.org";

        given(routePreferenceService.evaluate(any(), any()))
            .willReturn(RoutePreferenceService.RoutePreferenceResult.builder()
                .canProceed(true)
                .routeType(RoutePreferenceService.RouteType.BROWSER_ALLOWED)
                .build());

        given(browserWorkerClient.initSession(any(), any()))
            .willReturn(new SessionResult(true, "sess-portal", null));
        given(browserWorkerClient.initSession(any()))
            .willReturn(new SessionResult(true, "sess-portal", null));

        List<Map<String, Object>> authElements = List.of(
            Map.of("type", "email", "label", "Email or username"),
            Map.of("type", "password", "label", "Password"),
            Map.of("role", "button", "text", "Log in")
        );

        given(browserWorkerClient.executeAction(any(), any(), eq("NAVIGATE"), any()))
            .willReturn(new ActionResult(true, "Navigated", null, "base64",
                targetUrl, "Login - Portal", "Enter your credentials to log in", null,
                "obs-1", authElements, List.of(), null, "NORMAL"));

        given(browserWorkerClient.unparkSession(any()))
            .willReturn(new ActionResult(true, "Unparked", null, null,
                targetUrl, "Login - Portal", "Enter your credentials to log in", null,
                "obs-1", authElements, List.of(), null, "NORMAL"));

        Task task = createTask("access secure area on " + targetUrl);
        executorService.executeTask(task.getId());

        // Resolve first approval card
        long deadline = System.currentTimeMillis() + 20000;
        ApprovalRequest firstApproval = null;
        while (System.currentTimeMillis() < deadline) {
            var opt = approvalRepository.findFirstByTaskIdAndProposedActionAndStatus(task.getId(), "AUTH_GATE", "PENDING");
            if (opt.isPresent()) {
                firstApproval = opt.get();
                break;
            }
            Thread.sleep(200);
        }
        assertThat(firstApproval).isNotNull();
        firstApproval.setStatus("APPROVED");
        firstApproval.setUserResponse("{\"choice\":\"handoff\"}");
        approvalRepository.save(firstApproval);

        // Resolve second approval card
        deadline = System.currentTimeMillis() + 20000;
        ApprovalRequest secondApproval = null;
        while (System.currentTimeMillis() < deadline) {
            var opt = approvalRepository.findFirstByTaskIdAndProposedActionAndStatus(task.getId(), "AUTH_GATE", "PENDING");
            if (opt.isPresent() && !opt.get().getId().equals(firstApproval.getId())) {
                secondApproval = opt.get();
                break;
            }
            Thread.sleep(200);
        }
        assertThat(secondApproval).isNotNull();
        secondApproval.setStatus("APPROVED");
        secondApproval.setUserResponse("{\"choice\":\"handoff\"}");
        approvalRepository.save(secondApproval);

        // Wait for task to terminate (should fail on 3rd attempt without firing 3rd card)
        Task finalTask = waitForTerminal(task.getId());
        assertThat(finalTask.getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(finalTask.getFailureReason()).contains("Login not completed for " + domain);

        // Verify only 2 approval cards were ever created for this task
        long totalAuthApprovals = approvalRepository.findAll().stream()
            .filter(a -> task.getId().equals(a.getTaskId()) && "AUTH_GATE".equals(a.getProposedAction()))
            .count();
        assertThat(totalAuthApprovals).isEqualTo(2);
    }
}
