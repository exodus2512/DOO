package com.doo;

import com.doo.ai.AiProvider.ActionDecision;
import com.doo.domain.WorkflowType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for action validation rules, ActionDecision records, and WorkflowType enum.
 * These mirror the constants in TaskExecutorService without needing Spring context.
 */
class ActionValidationTest {

    // Duplicated from TaskExecutorService (private statics) so the test stays pure-unit.
    private static final List<String> ALLOWED_ACTIONS = List.of(
        "NAVIGATE", "CLICK", "FILL", "EXTRACT_TEXT", "SCREENSHOT",
        "SCROLL", "WAIT", "DONE", "GO_BACK"
    );

    private static final List<String> BLOCKED_ACTIONS = List.of(
        "EXECUTE_SCRIPT", "SHELL_COMMAND", "FILE_WRITE", "HTTP_REQUEST",
        "PURCHASE", "CHECKOUT", "SUBSCRIBE", "DELETE", "LOGIN"
    );

    // ── Allowlist / Blocklist integrity ────────────────────────────────────

    @Test
    @DisplayName("Allowed and blocked action lists have no overlap")
    void noOverlapBetweenAllowedAndBlocked() {
        for (String blocked : BLOCKED_ACTIONS) {
            assertThat(ALLOWED_ACTIONS).doesNotContain(blocked);
        }
    }

    @Test
    @DisplayName("Allowed actions list is not empty")
    void allowedActionsNotEmpty() {
        assertThat(ALLOWED_ACTIONS).isNotEmpty();
    }

    @Test
    @DisplayName("Blocked actions list is not empty")
    void blockedActionsNotEmpty() {
        assertThat(BLOCKED_ACTIONS).isNotEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "NAVIGATE", "CLICK", "FILL", "EXTRACT_TEXT", "SCREENSHOT",
        "SCROLL", "WAIT", "DONE", "GO_BACK"
    })
    @DisplayName("Each expected allowed action is in the allowlist")
    void expectedAllowedActionsPresent(String action) {
        assertThat(ALLOWED_ACTIONS).contains(action);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "EXECUTE_SCRIPT", "SHELL_COMMAND", "FILE_WRITE", "HTTP_REQUEST",
        "PURCHASE", "CHECKOUT", "SUBSCRIBE", "DELETE", "LOGIN"
    })
    @DisplayName("Each expected blocked action is in the blocklist")
    void expectedBlockedActionsPresent(String action) {
        assertThat(BLOCKED_ACTIONS).contains(action);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "EXECUTE_SCRIPT", "SHELL_COMMAND", "FILE_WRITE", "HTTP_REQUEST",
        "PURCHASE", "CHECKOUT", "SUBSCRIBE", "DELETE", "LOGIN"
    })
    @DisplayName("Blocked actions are never allowed")
    void blockedActionsNeverAllowed(String action) {
        assertThat(ALLOWED_ACTIONS).doesNotContain(action);
    }

    @Test
    @DisplayName("Unknown action type is neither allowed nor blocked")
    void unknownActionNotInEitherList() {
        String unknown = "MYSTERY_ACTION";
        assertThat(ALLOWED_ACTIONS).doesNotContain(unknown);
        // It may or may not be in BLOCKED_ACTIONS — the key is it's not allowed.
    }

    // ── ActionDecision record ──────────────────────────────────────────────

    @Test
    @DisplayName("ActionDecision carries all fields correctly")
    void actionDecisionCarriesFields() {
        Map<String, Object> args = Map.of("url", "https://example.com");
        ActionDecision decision = new ActionDecision(
            "NAVIGATE", args, "navigating to target", false, ""
        );

        assertThat(decision.actionType()).isEqualTo("NAVIGATE");
        assertThat(decision.args()).containsEntry("url", "https://example.com");
        assertThat(decision.rationale()).isEqualTo("navigating to target");
        assertThat(decision.requiresApproval()).isFalse();
        assertThat(decision.approvalReason()).isEmpty();
    }

    @Test
    @DisplayName("ActionDecision with approval flag set")
    void actionDecisionWithApproval() {
        ActionDecision decision = new ActionDecision(
            "CLICK", Map.of("selector", "#submit"), "submitting form",
            true, "Final form submission requires user approval"
        );

        assertThat(decision.requiresApproval()).isTrue();
        assertThat(decision.approvalReason()).contains("user approval");
    }

    @Test
    @DisplayName("ActionDecision with null args does not throw")
    void actionDecisionNullArgs() {
        ActionDecision decision = new ActionDecision(
            "SCREENSHOT", null, "capture", false, ""
        );
        assertThat(decision.args()).isNull();
    }

    @Test
    @DisplayName("ActionDecision with empty args map")
    void actionDecisionEmptyArgs() {
        ActionDecision decision = new ActionDecision(
            "DONE", Map.of(), "done", false, ""
        );
        assertThat(decision.args()).isEmpty();
    }

    // ── WorkflowType enum ──────────────────────────────────────────────────

    @Test
    @DisplayName("All workflow types exist")
    void allWorkflowTypesExist() {
        assertThat(WorkflowType.values()).hasSize(5);
        assertThat(WorkflowType.valueOf("WEB_RESEARCH")).isNotNull();
        assertThat(WorkflowType.valueOf("PRODUCT_COMPARISON")).isNotNull();
        assertThat(WorkflowType.valueOf("UI_INTERACTION")).isNotNull();
        assertThat(WorkflowType.valueOf("LLM_ONLY")).isNotNull();
        assertThat(WorkflowType.valueOf("UNKNOWN")).isNotNull();
        assertThat(WorkflowType.WEB_RESEARCH.requiresBrowser()).isTrue();
        assertThat(WorkflowType.LLM_ONLY.requiresBrowser()).isFalse();
        assertThat(WorkflowType.UNKNOWN.requiresBrowser()).isFalse();
    }

    @Test
    @DisplayName("Invalid workflow type throws IllegalArgumentException")
    void invalidWorkflowTypeThrows() {
        assertThatThrownBy(() -> WorkflowType.valueOf("NONEXISTENT"))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
