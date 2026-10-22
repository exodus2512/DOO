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
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

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

    @Test
    @DisplayName("BOT_BLOCK choice 'stop': fails the task immediately")
    void testBotBlockChoiceStopFailsTask() throws Exception {
        String targetUrl = "https://blocked-stop.com/page";
        String domain = "blocked-stop.com";

        given(routePreferenceService.evaluate(any(), any()))
            .willReturn(RoutePreferenceService.RoutePreferenceResult.builder()
                .canProceed(true)
                .routeType(RoutePreferenceService.RouteType.BROWSER_ALLOWED)
                .build());

        given(browserWorkerClient.initSession(any(), any()))
            .willReturn(new SessionResult(true, "sess-stop", null));
        given(browserWorkerClient.initSession(any()))
            .willReturn(new SessionResult(true, "sess-stop", null));

        given(browserWorkerClient.executeAction(any(), any(), eq("NAVIGATE"), any()))
            .willReturn(new ActionResult(true, "Navigated", null, "base64",
                targetUrl, "Attention Required! | Cloudflare", "Sorry, you have been blocked", null,
                "obs-1", List.of(), List.of(), null, "BOT_BLOCK"));

        given(browserWorkerClient.unparkSession(any()))
            .willReturn(new ActionResult(true, "Unparked", null, null,
                targetUrl, "Attention Required! | Cloudflare", "Sorry, you have been blocked", null,
                "obs-1", List.of(), List.of(), null, "BOT_BLOCK"));

        Task task = createTask("scrape blocked-stop.com at " + targetUrl);
        executorService.executeTask(task.getId());

        long deadline = System.currentTimeMillis() + 20000;
        ApprovalRequest approval = null;
        while (System.currentTimeMillis() < deadline) {
            var opt = approvalRepository.findFirstByTaskIdAndProposedActionAndStatus(task.getId(), "BOT_BLOCK", "PENDING");
            if (opt.isPresent()) {
                approval = opt.get();
                break;
            }
            Thread.sleep(200);
        }
        assertThat(approval).isNotNull();
        approval.setStatus("APPROVED");
        approval.setUserResponse("stop");
        approvalRepository.save(approval);

        Task finalTask = waitForTerminal(task.getId());
        assertThat(finalTask.getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(finalTask.getFailureReason()).contains("BOT_BLOCK: user chose to stop");

        TaskExecutorService.setDomainCooldown(domain, Duration.ofSeconds(-1));
    }

    @Test
    @DisplayName("BOT_BLOCK choice 'skip': adds domain to blockedDomains, navigates to about:blank, and prevents future navigation")
    void testBotBlockChoiceSkipNavigatesAboutBlankAndBlocksDomain() throws Exception {
        String targetUrl = "https://blocked-skip.com/page";
        String domain = "blocked-skip.com";

        given(routePreferenceService.evaluate(any(), any()))
            .willReturn(RoutePreferenceService.RoutePreferenceResult.builder()
                .canProceed(true)
                .routeType(RoutePreferenceService.RouteType.BROWSER_ALLOWED)
                .build());

        given(browserWorkerClient.initSession(any(), any()))
            .willReturn(new SessionResult(true, "sess-skip", null));
        given(browserWorkerClient.initSession(any()))
            .willReturn(new SessionResult(true, "sess-skip", null));

        given(browserWorkerClient.executeAction(any(), any(), eq("NAVIGATE"), any()))
            .willReturn(new ActionResult(true, "Navigated", null, "base64",
                targetUrl, "Attention Required! | Cloudflare", "Sorry, you have been blocked", null,
                "obs-1", List.of(), List.of(), null, "BOT_BLOCK"));

        given(browserWorkerClient.executeAction(any(), any(), eq("NAVIGATE"), argThat(m -> m != null && "about:blank".equals(m.get("url")))))
            .willReturn(new ActionResult(true, "Navigated about:blank", null, null,
                "about:blank", "about:blank", "", null, "obs-blank", List.of(), List.of(), null, "NORMAL"));

        given(browserWorkerClient.executeAction(any(), any(), eq("SCREENSHOT"), any()))
            .willReturn(new ActionResult(true, "Screenshot", null, "base64",
                "about:blank", "about:blank", "", null, "obs-ss", List.of(), List.of(), null, "NORMAL"));

        given(browserWorkerClient.unparkSession(any()))
            .willReturn(new ActionResult(true, "Unparked", null, null,
                targetUrl, "Attention Required! | Cloudflare", "Sorry, you have been blocked", null,
                "obs-1", List.of(), List.of(), null, "BOT_BLOCK"));

        Task task = createTask("research blocked-skip.com at " + targetUrl);
        executorService.executeTask(task.getId());

        long deadline = System.currentTimeMillis() + 20000;
        ApprovalRequest approval = null;
        while (System.currentTimeMillis() < deadline) {
            var opt = approvalRepository.findFirstByTaskIdAndProposedActionAndStatus(task.getId(), "BOT_BLOCK", "PENDING");
            if (opt.isPresent()) {
                approval = opt.get();
                break;
            }
            Thread.sleep(200);
        }
        assertThat(approval).isNotNull();
        approval.setStatus("APPROVED");
        approval.setUserResponse("skip");
        approvalRepository.save(approval);

        Task finalTask = waitForTerminal(task.getId());
        assertThat(finalTask.getStatus()).isNotEqualTo(TaskStatus.WAITING_FOR_USER);
        // Verify navigate to about:blank was invoked
        verify(browserWorkerClient, atLeastOnce()).executeAction(eq(task.getId()), any(), eq("NAVIGATE"),
            argThat(m -> m != null && "about:blank".equals(m.get("url"))));

        TaskExecutorService.setDomainCooldown(domain, Duration.ofSeconds(-1));
    }

    @Test
    @DisplayName("BOT_BLOCK choice 'open_live_view': calls live-token, pauses with LIVE_VIEW approval, and resumes on approval")
    void testBotBlockChoiceOpenLiveView() throws Exception {
        String targetUrl = "https://blocked-live.com/page";
        String domain = "blocked-live.com";

        given(routePreferenceService.evaluate(any(), any()))
            .willReturn(RoutePreferenceService.RoutePreferenceResult.builder()
                .canProceed(true)
                .routeType(RoutePreferenceService.RouteType.BROWSER_ALLOWED)
                .build());

        given(browserWorkerClient.initSession(any(), any()))
            .willReturn(new SessionResult(true, "sess-live", null));
        given(browserWorkerClient.initSession(any()))
            .willReturn(new SessionResult(true, "sess-live", null));

        given(browserWorkerClient.executeAction(any(), any(), eq("NAVIGATE"), any()))
            .willReturn(new ActionResult(true, "Navigated", null, "base64",
                targetUrl, "Attention Required! | Cloudflare", "Sorry, you have been blocked", null,
                "obs-1", List.of(), List.of(), null, "BOT_BLOCK"));

        given(browserWorkerClient.unparkSession(any()))
            .willReturn(new ActionResult(true, "Unparked", null, null,
                targetUrl, "Live Resumed", "Content now accessible", null,
                "obs-2", List.of(), List.of(), null, "NORMAL"));

        given(browserWorkerClient.getLiveToken(any()))
            .willReturn(new BrowserWorkerClient.LiveTokenResult(true, "token-12345", 600,
                "ws://localhost:3001/session/live", null));

        Task task = createTask("research on " + targetUrl);
        executorService.executeTask(task.getId());

        // 1. Resolve bot_block with open_live_view
        long deadline = System.currentTimeMillis() + 20000;
        ApprovalRequest bbApproval = null;
        while (System.currentTimeMillis() < deadline) {
            var opt = approvalRepository.findFirstByTaskIdAndProposedActionAndStatus(task.getId(), "BOT_BLOCK", "PENDING");
            if (opt.isPresent()) {
                bbApproval = opt.get();
                break;
            }
            Thread.sleep(200);
        }
        assertThat(bbApproval).isNotNull();
        bbApproval.setStatus("APPROVED");
        bbApproval.setUserResponse("open_live_view");
        approvalRepository.save(bbApproval);

        // 2. Wait for LIVE_VIEW approval card
        deadline = System.currentTimeMillis() + 20000;
        ApprovalRequest liveApproval = null;
        while (System.currentTimeMillis() < deadline) {
            var opt = approvalRepository.findFirstByTaskIdAndProposedActionAndStatus(task.getId(), "LIVE_VIEW", "PENDING");
            if (opt.isPresent()) {
                liveApproval = opt.get();
                break;
            }
            Thread.sleep(200);
        }
        assertThat(liveApproval).isNotNull();
        assertThat(liveApproval.getParameters().get("token")).isEqualTo("token-12345");
        assertThat(liveApproval.getParameters().get("wsUrl")).isEqualTo("ws://localhost:3001/session/live");

        // 3. User resumes
        liveApproval.setStatus("APPROVED");
        liveApproval.setUserResponse("resumed");
        approvalRepository.save(liveApproval);

        Task finalTask = waitForTerminal(task.getId());
        assertThat(finalTask.getStatus()).isNotEqualTo(TaskStatus.WAITING_FOR_USER);

        TaskExecutorService.setDomainCooldown(domain, Duration.ofSeconds(-1));
    }

    @Test
    @DisplayName("Repeated bot_block fails task after 2 attempts with Cloudflare error, no third card")
    void testBotBlockMaxAttemptsFailsTask() throws Exception {
        String targetUrl = "https://cf-blocked-twice.org/feed";
        String domain = "cf-blocked-twice.org";

        given(routePreferenceService.evaluate(any(), any()))
            .willReturn(RoutePreferenceService.RoutePreferenceResult.builder()
                .canProceed(true)
                .routeType(RoutePreferenceService.RouteType.BROWSER_ALLOWED)
                .build());

        given(browserWorkerClient.initSession(any(), any()))
            .willReturn(new SessionResult(true, "sess-cf", null));
        given(browserWorkerClient.initSession(any()))
            .willReturn(new SessionResult(true, "sess-cf", null));

        given(browserWorkerClient.getLiveToken(any()))
            .willReturn(new BrowserWorkerClient.LiveTokenResult(true, "token-cf", 600,
                "ws://localhost:3001/session/live", null));

        given(browserWorkerClient.executeAction(any(), any(), eq("NAVIGATE"), any()))
            .willReturn(new ActionResult(true, "Navigated", null, "base64",
                targetUrl, "Attention Required! | Cloudflare", "Sorry, you have been blocked", null,
                "obs-1", List.of(), List.of(), null, "BOT_BLOCK"));

        given(browserWorkerClient.unparkSession(any()))
            .willReturn(new ActionResult(true, "Unparked", null, null,
                targetUrl, "Attention Required! | Cloudflare", "Sorry, you have been blocked", null,
                "obs-1", List.of(), List.of(), null, "BOT_BLOCK"));

        Task task = createTask("fetch feed on " + targetUrl);
        executorService.executeTask(task.getId());

        // Resolve first card with open_live_view
        long deadline = System.currentTimeMillis() + 20000;
        ApprovalRequest firstApproval = null;
        while (System.currentTimeMillis() < deadline) {
            var opt = approvalRepository.findFirstByTaskIdAndProposedActionAndStatus(task.getId(), "BOT_BLOCK", "PENDING");
            if (opt.isPresent()) {
                firstApproval = opt.get();
                break;
            }
            Thread.sleep(200);
        }
        assertThat(firstApproval).isNotNull();
        firstApproval.setStatus("APPROVED");
        firstApproval.setUserResponse("open_live_view");
        approvalRepository.save(firstApproval);

        // Resolve first LIVE_VIEW
        deadline = System.currentTimeMillis() + 20000;
        ApprovalRequest firstLive = null;
        while (System.currentTimeMillis() < deadline) {
            var opt = approvalRepository.findFirstByTaskIdAndProposedActionAndStatus(task.getId(), "LIVE_VIEW", "PENDING");
            if (opt.isPresent()) {
                firstLive = opt.get();
                break;
            }
            Thread.sleep(200);
        }
        assertThat(firstLive).isNotNull();
        firstLive.setStatus("APPROVED");
        firstLive.setUserResponse("resumed");
        approvalRepository.save(firstLive);

        // Resolve second card with open_live_view
        deadline = System.currentTimeMillis() + 20000;
        ApprovalRequest secondApproval = null;
        while (System.currentTimeMillis() < deadline) {
            var opt = approvalRepository.findFirstByTaskIdAndProposedActionAndStatus(task.getId(), "BOT_BLOCK", "PENDING");
            if (opt.isPresent() && !opt.get().getId().equals(firstApproval.getId())) {
                secondApproval = opt.get();
                break;
            }
            Thread.sleep(200);
        }
        assertThat(secondApproval).isNotNull();
        secondApproval.setStatus("APPROVED");
        secondApproval.setUserResponse("open_live_view");
        approvalRepository.save(secondApproval);

        // Resolve second LIVE_VIEW
        deadline = System.currentTimeMillis() + 20000;
        ApprovalRequest secondLive = null;
        while (System.currentTimeMillis() < deadline) {
            var opt = approvalRepository.findFirstByTaskIdAndProposedActionAndStatus(task.getId(), "LIVE_VIEW", "PENDING");
            if (opt.isPresent() && !opt.get().getId().equals(firstLive.getId())) {
                secondLive = opt.get();
                break;
            }
            Thread.sleep(200);
        }
        assertThat(secondLive).isNotNull();
        secondLive.setStatus("APPROVED");
        secondLive.setUserResponse("resumed");
        approvalRepository.save(secondLive);

        // Wait for terminal state: should fail on 3rd attempt without firing 3rd card
        Task finalTask = waitForTerminal(task.getId());
        assertThat(finalTask.getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(finalTask.getFailureReason()).contains("site blocked automation (Cloudflare) at " + domain);

        // Verify only 2 bot block approval cards were created
        long totalBbApprovals = approvalRepository.findAll().stream()
            .filter(a -> task.getId().equals(a.getTaskId()) && "BOT_BLOCK".equals(a.getProposedAction()))
            .count();
        assertThat(totalBbApprovals).isEqualTo(2);

        TaskExecutorService.setDomainCooldown(domain, Duration.ofSeconds(-1));
    }
}
