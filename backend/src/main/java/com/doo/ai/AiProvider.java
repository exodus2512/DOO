package com.doo.ai;

import java.util.List;

/**
 * Provider-independent interface for AI model calls.
 * Implementations: GeminiAiProvider, DeterministicAiProvider (for tests/demo mode).
 */
public interface AiProvider {

    /**
     * Given a user request, classify the workflow type and produce a short rationale.
     */
    WorkflowClassification classifyWorkflow(String userRequest);

    /**
     * Given a task goal and success criteria, generate a plan (ordered list of step descriptions).
     */
    TaskPlan generatePlan(String userRequest, String successCriteria, WorkflowContext context);

    /**
     * Given the current browser state and task history, select the next action.
     */
    ActionDecision selectNextAction(ActionSelectionRequest request);

    /**
     * Verify that the task outcome meets the success criteria.
     */
    VerificationResult verify(VerificationRequest request);

    /**
     * Synthesize a final human-readable result from evidence.
     */
    String synthesizeResult(SynthesisRequest request);

    record WorkflowClassification(String workflowType, String rationale, String successCriteria) {}

    record WorkflowContext(String workflowType) {}

    record TaskPlan(List<String> steps, String successCriteria) {}

    record ActionDecision(
        String actionType,
        java.util.Map<String, Object> args,
        String rationale,
        boolean requiresApproval,
        String approvalReason
    ) {}

    record ActionSelectionRequest(
        String userRequest,
        String currentPlan,
        int currentStep,
        List<String> planSteps,
        String currentUrl,
        String pageTitle,
        String pageText,
        String accessibilityTree,
        List<String> recentActions,
        List<String> allowedActionTypes,
        String workflowType
    ) {}

    record VerificationRequest(
        String userRequest,
        String successCriteria,
        String workflowType,
        String finalUrl,
        String finalPageContent,
        List<String> visitedUrls,
        String synthesizedResult,
        List<String> evidenceSummary
    ) {
        public VerificationRequest(String userRequest, String successCriteria, String workflowType,
                                   String finalUrl, String finalPageContent, List<String> visitedUrls,
                                   String synthesizedResult) {
            this(userRequest, successCriteria, workflowType, finalUrl, finalPageContent,
                 visitedUrls, synthesizedResult, List.of());
        }
    }

    record VerificationResult(boolean passed, String notes, double confidence) {}

    record SynthesisRequest(
        String userRequest,
        String workflowType,
        List<String> visitedUrls,
        List<String> textSnippets,
        List<String> actionHistory
    ) {}
}
