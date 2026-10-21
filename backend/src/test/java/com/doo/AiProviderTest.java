package com.doo;

import com.doo.ai.AiProvider;
import com.doo.ai.AiProvider.*;
import com.doo.ai.GeminiAiProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

@DisplayName("AI Provider Tests")
class AiProviderTest {

    private AiProvider demoProvider;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        demoProvider = new GeminiAiProvider("", "gemini-1.5-flash", true, objectMapper);
    }

    @Nested
    @DisplayName("Deterministic Workflow Classification")
    class WorkflowClassificationTests {

        @Test
        @DisplayName("classifies research requests as WEB_RESEARCH")
        void classifiesResearch() {
            WorkflowClassification result = demoProvider.classifyWorkflow(
                "Research the top AI agent frameworks on GitHub");
            assertThat(result.workflowType()).isEqualTo("WEB_RESEARCH");
            assertThat(result.successCriteria()).isNotBlank();
        }

        @Test
        @DisplayName("classifies product/price requests as PRODUCT_COMPARISON")
        void classifiesProduct() {
            WorkflowClassification result = demoProvider.classifyWorkflow(
                "Show me the best product prices and where to buy");
            assertThat(result.workflowType()).isEqualTo("PRODUCT_COMPARISON");
        }

        @Test
        @DisplayName("classifies form/submit requests as UI_INTERACTION")
        void classifiesUiInteraction() {
            WorkflowClassification result = demoProvider.classifyWorkflow(
                "Fill out the test form and submit it");
            assertThat(result.workflowType()).isEqualTo("UI_INTERACTION");
        }

        @Test
        @DisplayName("defaults to WEB_RESEARCH for ambiguous requests")
        void classifiesDefault() {
            WorkflowClassification result = demoProvider.classifyWorkflow(
                "Tell me about quantum computing");
            assertThat(result.workflowType()).isEqualTo("WEB_RESEARCH");
        }

        @Test
        @DisplayName("classification always returns non-null fields")
        void classificationFieldsNotNull() {
            WorkflowClassification result = demoProvider.classifyWorkflow("anything");
            assertThat(result.workflowType()).isNotNull();
            assertThat(result.rationale()).isNotNull();
            assertThat(result.successCriteria()).isNotNull();
        }
    }

    @Nested
    @DisplayName("Deterministic Plan Generation")
    class PlanGenerationTests {

        @Test
        @DisplayName("generates WEB_RESEARCH plan with multiple steps")
        void webResearchPlan() {
            TaskPlan plan = demoProvider.generatePlan(
                "Research AI tools", "Find 3 tools",
                new WorkflowContext("WEB_RESEARCH"));
            assertThat(plan.steps()).isNotEmpty();
            assertThat(plan.steps().size()).isGreaterThanOrEqualTo(3);
            assertThat(plan.successCriteria()).isNotBlank();
        }

        @Test
        @DisplayName("generates PRODUCT_COMPARISON plan")
        void productComparisonPlan() {
            TaskPlan plan = demoProvider.generatePlan(
                "Compare Linear vs Jira", "Clear recommendation",
                new WorkflowContext("PRODUCT_COMPARISON"));
            assertThat(plan.steps()).isNotEmpty();
        }

        @Test
        @DisplayName("generates UI_INTERACTION plan")
        void uiInteractionPlan() {
            TaskPlan plan = demoProvider.generatePlan(
                "Fill the test form", "Form submitted",
                new WorkflowContext("UI_INTERACTION"));
            assertThat(plan.steps()).isNotEmpty();
        }

        @Test
        @DisplayName("generates default plan for UNKNOWN workflow")
        void unknownWorkflowPlan() {
            TaskPlan plan = demoProvider.generatePlan(
                "Do something", "Something done",
                new WorkflowContext("UNKNOWN"));
            assertThat(plan.steps()).isNotEmpty();
        }
    }

    @Nested
    @DisplayName("Deterministic Action Selection")
    class ActionSelectionTests {

        @Test
        @DisplayName("selects NAVIGATE when no current URL")
        void navigatesWhenNoUrl() {
            ActionDecision decision = demoProvider.selectNextAction(
                new ActionSelectionRequest(
                    "test request", "plan", 0, List.of("step1"),
                    "", "", "", "", List.of(), List.of("NAVIGATE", "CLICK"), "WEB_RESEARCH"
                ));
            assertThat(decision.actionType()).isEqualTo("NAVIGATE");
            assertThat(decision.args()).containsKey("url");
        }

        @Test
        @DisplayName("selects SCREENSHOT when on step 1 with URL")
        void screenshotsWhenOnPage() {
            ActionDecision decision = demoProvider.selectNextAction(
                new ActionSelectionRequest(
                    "test request", "plan", 1, List.of("step1", "step2"),
                    "https://example.com", "Example", "content", "",
                    List.of("NAVIGATE"), List.of("SCREENSHOT", "DONE"), "WEB_RESEARCH"
                ));
            assertThat(decision.actionType()).isEqualTo("SCREENSHOT");
        }

        @Test
        @DisplayName("selects DONE when past step 2")
        void doneWhenComplete() {
            ActionDecision decision = demoProvider.selectNextAction(
                new ActionSelectionRequest(
                    "test", "plan", 3, List.of("s1", "s2", "s3"),
                    "https://example.com", "Example", "content", "",
                    List.of("NAV", "SCREENSHOT"), List.of("DONE"), "WEB_RESEARCH"
                ));
            assertThat(decision.actionType()).isEqualTo("DONE");
        }

        @Test
        @DisplayName("action decision always has non-null fields")
        void actionFieldsNonNull() {
            ActionDecision decision = demoProvider.selectNextAction(
                new ActionSelectionRequest(
                    "test", "", 0, List.of(), null, "", "", "",
                    List.of(), List.of(), "WEB_RESEARCH"
                ));
            assertThat(decision.actionType()).isNotNull();
            assertThat(decision.args()).isNotNull();
            assertThat(decision.rationale()).isNotNull();
        }
    }

    @Nested
    @DisplayName("Verification in Demo Mode")
    class VerificationTests {

        @Test
        @DisplayName("demo mode verify returns passed=true with demo note")
        void demoVerify() {
            VerificationResult result = demoProvider.verify(
                new VerificationRequest("test", "criteria", "WEB_RESEARCH",
                    "https://example.com", "content", List.of("https://example.com"), "summary"));
            assertThat(result.passed()).isTrue();
            assertThat(result.notes()).contains("Demo mode");
            assertThat(result.confidence()).isGreaterThan(0.0);
        }
    }

    @Nested
    @DisplayName("Synthesis in Demo Mode")
    class SynthesisTests {

        @Test
        @DisplayName("demo mode synthesis mentions demo mode")
        void demoSynthesis() {
            String result = demoProvider.synthesizeResult(
                new SynthesisRequest("test", "WEB_RESEARCH",
                    List.of("https://example.com"), List.of("snippet"), List.of("action")));
            assertThat(result).containsIgnoringCase("demo mode");
        }
    }

    @Nested
    @DisplayName("Provider Availability")
    class AvailabilityTests {

        @Test
        @DisplayName("provider with empty API key uses deterministic mode")
        void emptyKeyUsesDeterministic() {
            AiProvider provider = new GeminiAiProvider("", "model", false, objectMapper);
            WorkflowClassification result = provider.classifyWorkflow("research AI tools");
            assertThat(result.workflowType()).isEqualTo("WEB_RESEARCH");
        }

        @Test
        @DisplayName("provider with blank API key uses deterministic mode")
        void blankKeyUsesDeterministic() {
            AiProvider provider = new GeminiAiProvider("   ", "model", false, objectMapper);
            WorkflowClassification result = provider.classifyWorkflow("fill the form");
            assertThat(result.workflowType()).isEqualTo("UI_INTERACTION");
        }

        @Test
        @DisplayName("provider with null API key uses deterministic mode")
        void nullKeyUsesDeterministic() {
            AiProvider provider = new GeminiAiProvider(null, "model", false, objectMapper);
            WorkflowClassification result = provider.classifyWorkflow("show me the product price list");
            assertThat(result.workflowType()).isEqualTo("PRODUCT_COMPARISON");
        }

        @Test
        @DisplayName("provider with demo-mode=true uses deterministic mode even with API key")
        void demoModeForcedDeterministic() {
            AiProvider provider = new GeminiAiProvider("real-api-key", "model", true, objectMapper);
            WorkflowClassification result = provider.classifyWorkflow("research topic");
            assertThat(result.workflowType()).isEqualTo("WEB_RESEARCH");
        }
    }

    @Nested
    @DisplayName("Record Contract Tests")
    class RecordContractTests {

        @Test
        @DisplayName("WorkflowClassification carries all fields")
        void workflowClassificationFields() {
            var wc = new WorkflowClassification("WEB_RESEARCH", "reason", "criteria");
            assertThat(wc.workflowType()).isEqualTo("WEB_RESEARCH");
            assertThat(wc.rationale()).isEqualTo("reason");
            assertThat(wc.successCriteria()).isEqualTo("criteria");
        }

        @Test
        @DisplayName("TaskPlan carries steps and criteria")
        void taskPlanFields() {
            var tp = new TaskPlan(List.of("step1", "step2"), "success");
            assertThat(tp.steps()).hasSize(2);
            assertThat(tp.successCriteria()).isEqualTo("success");
        }

        @Test
        @DisplayName("VerificationResult carries all fields")
        void verificationResultFields() {
            var vr = new VerificationResult(true, "notes", 0.95);
            assertThat(vr.passed()).isTrue();
            assertThat(vr.notes()).isEqualTo("notes");
            assertThat(vr.confidence()).isEqualTo(0.95);
        }

        @Test
        @DisplayName("ActionDecision carries approval fields")
        void actionDecisionApproval() {
            var ad = new ActionDecision("CLICK", Map.of("selector", "#btn"),
                "clicking button", true, "needs approval");
            assertThat(ad.requiresApproval()).isTrue();
            assertThat(ad.approvalReason()).isEqualTo("needs approval");
        }
    }
}
