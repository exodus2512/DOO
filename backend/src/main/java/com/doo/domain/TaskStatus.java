package com.doo.domain;

public enum TaskStatus {
    QUEUED,
    RUNNING,
    WAITING_FOR_USER,
    VERIFYING,
    COMPLETED,
    PARTIAL,
    FAILED,
    CANCELLED;

    public boolean isTerminal() {
        return this == COMPLETED || this == PARTIAL || this == FAILED || this == CANCELLED;
    }

    public boolean canTransitionTo(TaskStatus next) {
        return switch (this) {
            case QUEUED -> next == RUNNING || next == CANCELLED;
            case RUNNING -> next == WAITING_FOR_USER || next == VERIFYING
                    || next == COMPLETED || next == PARTIAL || next == FAILED || next == CANCELLED;
            case WAITING_FOR_USER -> next == RUNNING || next == CANCELLED || next == FAILED;
            case VERIFYING -> next == COMPLETED || next == PARTIAL || next == FAILED;
            case COMPLETED, PARTIAL, FAILED, CANCELLED -> false;
        };
    }
}
