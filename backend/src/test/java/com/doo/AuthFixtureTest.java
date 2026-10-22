package com.doo;

import com.doo.ai.AiProvider;
import com.doo.ai.AiProvider.*;
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
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

@SpringBootTest(properties = {
    "doo.ai.demo-mode=true",
    "doo.ai.gemini.api-key="
})
@ActiveProfiles("h2")
@DisplayName("Auth Fixture Tests")
class AuthFixtureTest {

    @Autowired private TaskRepository taskRepository;
    @Autowired private ApprovalRequestRepository approvalRepository;
    @Autowired private TaskExecutorService executorService;
    @Autowired private AiProvider aiProvider;
    @MockBean  private BrowserWorkerClient browserWorkerClient;
    @MockBean  private RoutePreferenceService routePreferenceService;

    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(15);

    private Task createTask(String request) {
        Task task = Task.builder()
            .userRequest(request)
            .status(TaskStatus.QUEUED)
            .workflowType(WorkflowType.UI_INTERACTION)
            .currentStep(0)
            .build();
        return taskRepository.save(task);
    }

    @Test
    @DisplayName("Landing page with only a 'Sign in' button -> model must CLICK it, not ASK_USER for credentials")
    void testLandingPageWithOnlySignInButtonClickNotAskUser() {
        // Given observation of landing page with only a "Sign in" button and no input fields
        List<Map<String, Object>> elements = List.of(
            Map.of("index", 1, "role", "button", "text", "Sign in", "tag", "button")
        );

        ActionSelectionRequest request = new ActionSelectionRequest(
            "Log in to my account on http://localhost:8081/signin-landing.html",
            "[INTERACT] Sign in to account",
            1,
            List.of("[NAVIGATE] Go to signin landing", "[INTERACT] Click sign in"),
            "http://localhost:8081/signin-landing.html",
            "Landing Page with Sign In",
            "Welcome to Example Service. Please sign in to access your dashboard.",
            "",
            List.of("NAVIGATE to http://localhost:8081/signin-landing.html"),
            List.of("NAVIGATE", "CLICK", "FILL", "ASK_USER"),
            "UI_INTERACTION",
            "obs-landing-1",
            elements,
            null
        );

        ActionDecision decision = aiProvider.selectNextAction(request);

        // The model MUST CLICK the Sign in button, NEVER ASK_USER for credentials
        assertThat(decision.actionType()).isEqualTo("CLICK");
        assertThat(decision.actionType()).isNotEqualTo("ASK_USER");
        assertThat(decision.args()).containsEntry("element_index", 1);
        assertThat(decision.args()).doesNotContainKey("fields");
    }

    @Test
    @DisplayName("Sign-in modal with Google and Email -> backend emits AUTH_GATE approval with options [handoff, oauth_note, email_link] and NO password option")
    void testSignInModalEmitsAuthGateWithOptionsHandoffOauthEmailNoPassword() throws Exception {
        String modalUrl = "http://localhost:8081/signin-modal.html";

        given(routePreferenceService.evaluate(any(), any()))
            .willReturn(RoutePreferenceService.RoutePreferenceResult.builder()
                .canProceed(true)
                .routeType(RoutePreferenceService.RouteType.BROWSER_ALLOWED)
                .build());

        given(browserWorkerClient.initSession(any(), any()))
            .willReturn(new SessionResult(true, "sess-modal", null));
        given(browserWorkerClient.initSession(any()))
            .willReturn(new SessionResult(true, "sess-modal", null));

        // Observation on sign-in modal with "Sign in with Google" and "Sign in with email" buttons (no password field)
        List<Map<String, Object>> modalElements = List.of(
            Map.of("index", 1, "role", "button", "text", "Sign in with Google", "tag", "button"),
            Map.of("index", 2, "role", "button", "text", "Sign in with email", "tag", "button")
        );

        given(browserWorkerClient.executeAction(any(), any(), eq("NAVIGATE"), any()))
            .willReturn(new ActionResult(true, "Navigated", null, "base64img",
                modalUrl, "Sign In Modal",
                "Sign in to your account. Sign in with Google. Sign in with email.",
                null, "obs-modal-1", modalElements, List.of(), null, "NORMAL"));

        // 1. Verify surface detection directly
        TaskExecutorService.AuthSurface surface = TaskExecutorService.detectAuthSurface(
            modalElements,
            "Sign in to your account. Sign in with Google. Sign in with email.",
            modalUrl
        );
        assertThat(surface.isAuthPage()).isTrue();
        assertThat(surface.hasPasswordForm()).isFalse();
        assertThat(surface.hasEmailOnlyForm()).isTrue();
        assertThat(surface.oauthProviders()).contains("Google");

        // 2. Verify backend emits AUTH_GATE approval with handoff, oauth_note, email_link and no password
        Task task = createTask("Sign in on " + modalUrl);
        executorService.executeTask(task.getId());

        // Wait for backend to pause for user with AUTH_GATE approval
        long deadline = System.currentTimeMillis() + POLL_TIMEOUT.toMillis();
        ApprovalRequest authApproval = null;
        while (System.currentTimeMillis() < deadline) {
            var opt = approvalRepository.findFirstByTaskIdAndProposedActionAndStatus(task.getId(), "AUTH_GATE", "PENDING");
            if (opt.isPresent()) {
                authApproval = opt.get();
                break;
            }
            Thread.sleep(200);
        }

        assertThat(authApproval).isNotNull();
        assertThat(authApproval.getProposedAction()).isEqualTo("AUTH_GATE");

        Map<String, Object> params = authApproval.getParameters();
        assertThat(params).isNotNull();
        assertThat(params.get("type")).isEqualTo("auth_gate");
        assertThat(params.get("hasPasswordForm")).isEqualTo(false);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> options = (List<Map<String, Object>>) params.get("options");
        assertThat(options).isNotNull();

        List<String> optionValues = options.stream()
            .map(opt -> String.valueOf(opt.get("value")))
            .toList();

        // Must emit options: handoff, oauth_note, email_link
        assertThat(optionValues).contains("handoff", "oauth_note", "email_link");
        // Must NOT emit password option
        assertThat(optionValues).doesNotContain("password");

        // Clean up: reject or approve to let task terminate cleanly
        authApproval.setStatus("REJECTED");
        authApproval.setUserResponse("cancelled in test");
        approvalRepository.save(authApproval);
    }
}
