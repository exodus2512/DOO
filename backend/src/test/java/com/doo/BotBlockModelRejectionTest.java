package com.doo;

import com.doo.ai.AiProvider;
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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@SpringBootTest(properties = {
    "doo.ai.demo-mode=false"
})
@ActiveProfiles("h2")
@DisplayName("Bot Block Action Rejection Test")
class BotBlockModelRejectionTest {

    @Autowired private TaskRepository taskRepository;
    @Autowired private TaskExecutorService executorService;
    @MockBean  private BrowserWorkerClient browserWorkerClient;
    @MockBean  private RoutePreferenceService routePreferenceService;
    @MockBean  private AiProvider aiProvider;

    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(15);

    @Test
    @DisplayName("Model returning WAIT on a BOT_BLOCK page is rejected, not executed, and fails the step")
    void testWaitRejectedOnBotBlockPage() throws Exception {
        String targetUrl = "https://medium.com/article";
        given(routePreferenceService.evaluate(any(), any()))
            .willReturn(RoutePreferenceService.RoutePreferenceResult.builder()
                .canProceed(true)
                .routeType(RoutePreferenceService.RouteType.BROWSER_ALLOWED)
                .build());

        given(browserWorkerClient.initSession(any(), any()))
            .willReturn(new SessionResult(true, "sess-1", null));
        given(browserWorkerClient.initSession(any()))
            .willReturn(new SessionResult(true, "sess-1", null));

        // Setup AI Provider mock: classify and generate plan
        given(aiProvider.classifyWorkflow(any()))
            .willReturn(new AiProvider.WorkflowClassification("WEB_RESEARCH", "Research", "Find info"));
        given(aiProvider.generatePlan(any(), any(), any()))
            .willReturn(new AiProvider.TaskPlan(List.of("Navigate and extract"), "Success criteria"));

        // When the model selects action, it detects a BOT_BLOCK page and proposes WAIT
        given(aiProvider.selectNextAction(any()))
            .willReturn(new AiProvider.ActionDecision(
                "WAIT",
                Map.of("duration", 10),
                "Waiting for block page to reload",
                false,
                null,
                "Cloudflare block page visible",
                "BOT_BLOCK",
                "blocked",
                List.of("Cloudflare Ray ID"),
                "Waiting to see if challenge passes",
                "Page clears"
            ));

        Task task = Task.builder()
            .userRequest("Read article on " + targetUrl)
            .status(TaskStatus.QUEUED)
            .workflowType(WorkflowType.WEB_RESEARCH)
            .currentStep(0)
            .build();
        task = taskRepository.save(task);

        executorService.executeTask(task.getId());

        // Wait for task to reach terminal status
        long deadline = System.currentTimeMillis() + POLL_TIMEOUT.toMillis();
        Task terminalTask = null;
        while (System.currentTimeMillis() < deadline) {
            Task t = taskRepository.findById(task.getId()).orElseThrow();
            if (t.getStatus().isTerminal()) {
                terminalTask = t;
                break;
            }
            Thread.sleep(200);
        }

        assertThat(terminalTask).isNotNull();
        assertThat(terminalTask.getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(terminalTask.getFailureReason()).contains("site blocked automation (Cloudflare)");

        // Assert WAIT was NOT executed on the browser worker
        verify(browserWorkerClient, never()).executeAction(any(), any(), eq("WAIT"), any());
    }
}
