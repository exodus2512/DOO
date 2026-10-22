package com.doo;

import com.doo.domain.Task;
import com.doo.domain.TaskStatus;
import com.doo.domain.WorkflowType;
import com.doo.repository.TaskRepository;
import com.doo.service.TaskExecutorService;
import com.doo.service.TaskExecutorService.PendingAuthLogin;
import com.doo.worker.BrowserWorkerClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@SpringBootTest(properties = {
    "doo.ai.demo-mode=true",
    "doo.ai.gemini.api-key="
})
@ActiveProfiles("h2")
@DisplayName("PendingAuthLogin Tests")
class PendingAuthLoginTest {

    @Autowired private TaskRepository taskRepository;
    @Autowired private TaskExecutorService executorService;
    @MockBean  private BrowserWorkerClient browserWorkerClient;

    private Task testTask;

    @BeforeEach
    void setup() {
        testTask = Task.builder()
            .userRequest("Sign in to example.com")
            .status(TaskStatus.RUNNING)
            .workflowType(WorkflowType.UI_INTERACTION)
            .currentStep(0)
            .build();
        testTask = taskRepository.save(testTask);
        executorService.setPendingAuthLogin(null);
    }

    @Test
    @DisplayName("Model directive is present while pending, and gone after verification")
    void testDirectivePresentWhilePendingAndGoneAfterVerification() {
        String baseHints = "USER-PROVIDED VALUES:\n  Value available: __user:email__";
        PendingAuthLogin pending = new PendingAuthLogin("example.com", "password", 1);
        executorService.setPendingAuthLogin(pending);

        // 1. Directive is injected while pendingAuthLogin is set
        String hintsWithDirective = executorService.getEffectiveCredentialHints(baseHints, executorService.getPendingAuthLogin());
        assertThat(hintsWithDirective).contains("The user provided password credentials for example.com. Complete the sign-in now: FILL the login fields using the provided placeholders, then CLICK the form's primary Log in / Continue / Sign in button (NOT OAuth buttons, NOT sign-up links). If no password field is visible, CLICK the control that reveals it (e.g. 'Continue with email') first.");
        assertThat(hintsWithDirective).contains(baseHints);

        // Also test email_link choice
        PendingAuthLogin emailPending = new PendingAuthLogin("site.org", "email_link", 2);
        String emailDirective = executorService.getEffectiveCredentialHints("", emailPending);
        assertThat(emailDirective).isEqualTo("The user provided email_link credentials for site.org. Complete the sign-in now: FILL the login fields using the provided placeholders, then CLICK the form's primary Log in / Continue / Sign in button (NOT OAuth buttons, NOT sign-up links). If no password field is visible, CLICK the control that reveals it (e.g. 'Continue with email') first.");

        // 2. Simulate loop verification succeeding on logged-in page
        List<Map<String, Object>> dashboardElements = List.of(
            Map.of("role", "button", "text", "Sign out", "tag", "button")
        );
        boolean verified = executorService.checkPendingAuthVerification(
            2,
            "https://example.com/dashboard",
            "Welcome to Dashboard! Sign out",
            dashboardElements,
            testTask.getId(),
            testTask
        );

        assertThat(verified).isTrue();
        assertThat(executorService.getPendingAuthLogin()).isNull();
        verify(browserWorkerClient).saveStorageState(eq(testTask.getId()), eq("example.com"));

        // 3. Directive is gone after verification cleared pendingAuthLogin
        String hintsAfterVerification = executorService.getEffectiveCredentialHints(baseHints, executorService.getPendingAuthLogin());
        assertThat(hintsAfterVerification).isEqualTo(baseHints);
        assertThat(hintsAfterVerification).doesNotContain("The user provided");
    }

    @Test
    @DisplayName("After 5 steps without success, loop fails with 'login did not complete for <domain>'")
    void testPendingAuthLoginFailsAfter5StepsWithoutSuccess() {
        PendingAuthLogin pending = new PendingAuthLogin("login.example.com", "password", 1);
        executorService.setPendingAuthLogin(pending);

        List<Map<String, Object>> loginFormElements = List.of(
            Map.of("role", "textbox", "type", "text", "tag", "input"),
            Map.of("role", "textbox", "type", "password", "tag", "input")
        );

        // Steps 1 to 5 (step - startStep < 5): should not fail yet
        for (int step = 1; step <= 5; step++) {
            boolean continued = executorService.checkPendingAuthVerification(
                step,
                "https://login.example.com/login",
                "Sign in to your account",
                loginFormElements,
                testTask.getId(),
                testTask
            );
            assertThat(continued).as("Step %d should continue (not timeout yet)", step).isTrue();
            assertThat(executorService.getPendingAuthLogin()).isNotNull();
        }

        // Step 6 (step - startStep = 6 - 1 = 5 >= 5): fails
        boolean continuedAtStep6 = executorService.checkPendingAuthVerification(
            6,
            "https://login.example.com/login",
            "Sign in to your account",
            loginFormElements,
            testTask.getId(),
            testTask
        );

        assertThat(continuedAtStep6).isFalse();
        Task failedTask = taskRepository.findById(testTask.getId()).orElseThrow();
        assertThat(failedTask.getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(failedTask.getFailureReason()).isEqualTo("login did not complete for login.example.com");
    }

    @Test
    @DisplayName("Success at loop-top clears pendingAuthLogin and saves storage state")
    void testSuccessfulLoginSavesStorageStateAndClearsPending() {
        PendingAuthLogin pending = new PendingAuthLogin("secure.service.com", "password", 2);
        executorService.setPendingAuthLogin(pending);

        List<Map<String, Object>> profileElements = List.of(
            Map.of("role", "link", "text", "My Account", "href", "/account")
        );

        boolean ok = executorService.checkPendingAuthVerification(
            3,
            "https://secure.service.com/account",
            "My Account overview",
            profileElements,
            testTask.getId(),
            testTask
        );

        assertThat(ok).isTrue();
        assertThat(executorService.getPendingAuthLogin()).isNull();
        verify(browserWorkerClient).saveStorageState(eq(testTask.getId()), eq("secure.service.com"));
    }
}
