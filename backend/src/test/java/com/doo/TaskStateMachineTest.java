package com.doo;

import com.doo.domain.Task;
import com.doo.domain.TaskStatus;
import com.doo.domain.WorkflowType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for the Task state machine transitions.
 */
class TaskStateMachineTest {

    private Task buildTask(TaskStatus status) {
        Task task = new Task();
        task.setStatus(status);
        task.setWorkflowType(WorkflowType.UNKNOWN);
        task.setCurrentStep(0);
        return task;
    }

    @Test
    @DisplayName("QUEUED -> RUNNING is allowed")
    void queuedToRunning() {
        Task task = buildTask(TaskStatus.QUEUED);
        assertThatNoException().isThrownBy(() -> task.transitionTo(TaskStatus.RUNNING));
        assertThat(task.getStatus()).isEqualTo(TaskStatus.RUNNING);
        assertThat(task.getStartedAt()).isNotNull();
    }

    @Test
    @DisplayName("QUEUED -> CANCELLED is allowed")
    void queuedToCancelled() {
        Task task = buildTask(TaskStatus.QUEUED);
        task.transitionTo(TaskStatus.CANCELLED);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.CANCELLED);
        assertThat(task.getCompletedAt()).isNotNull();
    }

    @Test
    @DisplayName("RUNNING -> VERIFYING is allowed")
    void runningToVerifying() {
        Task task = buildTask(TaskStatus.RUNNING);
        task.transitionTo(TaskStatus.VERIFYING);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.VERIFYING);
    }

    @Test
    @DisplayName("RUNNING -> WAITING_FOR_USER is allowed")
    void runningToWaitingForUser() {
        Task task = buildTask(TaskStatus.RUNNING);
        task.transitionTo(TaskStatus.WAITING_FOR_USER);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.WAITING_FOR_USER);
    }

    @Test
    @DisplayName("WAITING_FOR_USER -> RUNNING is allowed (resume)")
    void waitingForUserToRunning() {
        Task task = buildTask(TaskStatus.WAITING_FOR_USER);
        task.transitionTo(TaskStatus.RUNNING);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.RUNNING);
    }

    @Test
    @DisplayName("VERIFYING -> COMPLETED is allowed")
    void verifyingToCompleted() {
        Task task = buildTask(TaskStatus.VERIFYING);
        task.transitionTo(TaskStatus.COMPLETED);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(task.getCompletedAt()).isNotNull();
    }

    @Test
    @DisplayName("VERIFYING -> PARTIAL is allowed")
    void verifyingToPartial() {
        Task task = buildTask(TaskStatus.VERIFYING);
        task.transitionTo(TaskStatus.PARTIAL);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.PARTIAL);
    }

    @Test
    @DisplayName("COMPLETED -> any transition throws")
    void completedIsTerminal() {
        Task task = buildTask(TaskStatus.COMPLETED);
        assertThatThrownBy(() -> task.transitionTo(TaskStatus.RUNNING))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("COMPLETED");
    }

    @Test
    @DisplayName("FAILED -> any transition throws")
    void failedIsTerminal() {
        Task task = buildTask(TaskStatus.FAILED);
        assertThatThrownBy(() -> task.transitionTo(TaskStatus.RUNNING))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("CANCELLED -> any transition throws")
    void cancelledIsTerminal() {
        Task task = buildTask(TaskStatus.CANCELLED);
        assertThatThrownBy(() -> task.transitionTo(TaskStatus.RUNNING))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("QUEUED -> COMPLETED directly throws (skip steps)")
    void queuedToCompletedIsInvalid() {
        Task task = buildTask(TaskStatus.QUEUED);
        assertThatThrownBy(() -> task.transitionTo(TaskStatus.COMPLETED))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("Terminal state check")
    void terminalStateCheck() {
        assertThat(TaskStatus.COMPLETED.isTerminal()).isTrue();
        assertThat(TaskStatus.FAILED.isTerminal()).isTrue();
        assertThat(TaskStatus.CANCELLED.isTerminal()).isTrue();
        assertThat(TaskStatus.PARTIAL.isTerminal()).isTrue();
        assertThat(TaskStatus.RUNNING.isTerminal()).isFalse();
        assertThat(TaskStatus.QUEUED.isTerminal()).isFalse();
    }
}
