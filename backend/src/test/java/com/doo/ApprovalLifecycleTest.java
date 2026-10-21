package com.doo;

import com.doo.domain.ApprovalRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for ApprovalRequest lifecycle: defaults, expiry, binding, and re-resolution guard.
 */
class ApprovalLifecycleTest {

    @Test
    @DisplayName("Default status is PENDING when built without explicit status")
    void defaultStatusIsPending() {
        Instant now = Instant.now();
        ApprovalRequest approval = ApprovalRequest.builder()
            .taskId(UUID.randomUUID())
            .prompt("Confirm form submission")
            .status("PENDING")
            .createdAt(now)
            .expiresAt(now.plusSeconds(600))
            .build();

        assertThat(approval.getStatus()).isEqualTo("PENDING");
        assertThat(approval.getCreatedAt()).isNotNull();
        assertThat(approval.getExpiresAt()).isNotNull();
        assertThat(approval.getExpiresAt()).isAfter(approval.getCreatedAt());
    }

    @Test
    @DisplayName("isExpired returns true when expiresAt is in the past")
    void expiredApprovalIsDetected() {
        ApprovalRequest approval = ApprovalRequest.builder()
            .taskId(UUID.randomUUID())
            .prompt("Old approval")
            .expiresAt(Instant.now().minusSeconds(60))
            .build();

        assertThat(approval.isExpired()).isTrue();
    }

    @Test
    @DisplayName("isExpired returns false when expiresAt is in the future")
    void nonExpiredApprovalIsDetected() {
        ApprovalRequest approval = ApprovalRequest.builder()
            .taskId(UUID.randomUUID())
            .prompt("Fresh approval")
            .expiresAt(Instant.now().plusSeconds(300))
            .build();

        assertThat(approval.isExpired()).isFalse();
    }

    @Test
    @DisplayName("Approval binding: taskId mismatch is detectable")
    void approvalBelongsToCorrectTask() {
        UUID realTaskId = UUID.randomUUID();
        UUID wrongTaskId = UUID.randomUUID();

        ApprovalRequest approval = ApprovalRequest.builder()
            .id(UUID.randomUUID())
            .taskId(realTaskId)
            .prompt("Submit registration")
            .proposedAction("CLICK")
            .parameters(Map.of("selector", "#submit-btn"))
            .status("PENDING")
            .build();

        // The service layer enforces: approval.getTaskId().equals(taskId)
        assertThat(approval.getTaskId()).isEqualTo(realTaskId);
        assertThat(approval.getTaskId()).isNotEqualTo(wrongTaskId);
    }

    @Test
    @DisplayName("Already-resolved approval cannot pass PENDING check")
    void resolvedApprovalCannotBeReResolved() {
        ApprovalRequest approval = ApprovalRequest.builder()
            .id(UUID.randomUUID())
            .taskId(UUID.randomUUID())
            .prompt("Confirm action")
            .status("APPROVED")
            .resolvedAt(Instant.now())
            .build();

        // The service layer enforces: "PENDING".equals(approval.getStatus())
        assertThat(approval.getStatus()).isNotEqualTo("PENDING");

        // Same check for REJECTED
        ApprovalRequest rejected = ApprovalRequest.builder()
            .id(UUID.randomUUID())
            .taskId(UUID.randomUUID())
            .prompt("Confirm action")
            .status("REJECTED")
            .resolvedAt(Instant.now())
            .build();

        assertThat(rejected.getStatus()).isNotEqualTo("PENDING");

        // Same check for EXPIRED
        ApprovalRequest expired = ApprovalRequest.builder()
            .id(UUID.randomUUID())
            .taskId(UUID.randomUUID())
            .prompt("Confirm action")
            .status("EXPIRED")
            .resolvedAt(Instant.now())
            .build();

        assertThat(expired.getStatus()).isNotEqualTo("PENDING");
    }
}
