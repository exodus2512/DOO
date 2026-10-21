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

    /**
     * Answer a question directly without browser interaction (LLM_ONLY path).
     */
    String directAnswer(String userRequest);

    /**
     * Replan from the current state after a failure or when the original plan can't proceed.
     */
    TaskPlan replan(ReplanRequest request);

    record ReplanRequest(
        String userRequest,
        String workflowType,
        List<String> originalSteps,
        int failedAtStep,
        String failureReason,
        String currentUrl,
        String pageTitle,
        List<String> completedActions
    ) {}

    record WorkflowClassification(
        String workflowType,
        String rationale,
        String successCriteria,
        String goal,
        List<String> entities,
        String deliverable,
        List<String> missingInputs
    ) {
        public WorkflowClassification(String workflowType, String rationale, String successCriteria) {
            this(workflowType, rationale, successCriteria, null, List.of(), null, List.of());
        }
    }

    record WorkflowContext(String workflowType) {}

    record TaskPlan(List<String> steps, String successCriteria) {}

    record ActionDecision(
        String actionType,
        java.util.Map<String, Object> args,
        String rationale,
        boolean requiresApproval,
        String approvalReason,
        // Extended reasoning schema fields
        String observation,     // 1-2 sentences about the current state
        String pageState,       // LOGIN|CAPTCHA|OTP|BOT_BLOCK|FORM|CONTENT|ERROR|OTHER
        String goalProgress,    // "done" or remaining goal
        List<String> blockers,  // blocking issues
        String why,             // single sentence tying action to observation
        String expected         // what should be true after this action
    ) {
        // Backward-compat constructor
        public ActionDecision(String actionType, java.util.Map<String, Object> args,
                String rationale, boolean requiresApproval, String approvalReason) {
            this(actionType, args, rationale, requiresApproval, approvalReason,
                 null, "OTHER", null, List.of(), null, null);
        }
    }

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
        String workflowType,
        // Structured perception (null when no observation available yet)
        String observationId,
        List<java.util.Map<String, Object>> interactiveElements,
        // Credential hints for vault/gate — placeholders, never actual passwords
        String credentialHints,
        // Scroll position: {scrollY, pageHeight, viewportHeight}
        java.util.Map<String, Object> scroll,
        // How many text extractions have been collected so far
        int extractedSnippetCount
    ) {
        // Backward-compat constructor for tests that don't have observation data
        public ActionSelectionRequest(
            String userRequest, String currentPlan, int currentStep, List<String> planSteps,
            String currentUrl, String pageTitle, String pageText, String accessibilityTree,
            List<String> recentActions, List<String> allowedActionTypes, String workflowType) {
            this(userRequest, currentPlan, currentStep, planSteps, currentUrl, pageTitle,
                 pageText, accessibilityTree, recentActions, allowedActionTypes, workflowType,
                 null, null, null, null, 0);
        }

        public ActionSelectionRequest(
            String userRequest, String currentPlan, int currentStep, List<String> planSteps,
            String currentUrl, String pageTitle, String pageText, String accessibilityTree,
            List<String> recentActions, List<String> allowedActionTypes, String workflowType,
            String observationId, List<java.util.Map<String, Object>> interactiveElements,
            String credentialHints) {
            this(userRequest, currentPlan, currentStep, planSteps, currentUrl, pageTitle,
                 pageText, accessibilityTree, recentActions, allowedActionTypes, workflowType,
                 observationId, interactiveElements, credentialHints, null, 0);
        }
    }

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

    record VerificationResult(boolean passed, String notes, double confidence, List<String> unsupportedClaims) {
        public VerificationResult(boolean passed, String notes, double confidence) {
            this(passed, notes, confidence, List.of());
        }
    }

    record SynthesisRequest(
        String userRequest,
        String workflowType,
        String deliverableType,
        List<String> visitedUrls,
        List<String> textSnippets,
        List<String> actionHistory
    ) {
        public SynthesisRequest(String userRequest, String workflowType,
                                List<String> visitedUrls, List<String> textSnippets,
                                List<String> actionHistory) {
            this(userRequest, workflowType, null, visitedUrls, textSnippets, actionHistory);
        }
    }
}
