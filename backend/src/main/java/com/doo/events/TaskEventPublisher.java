package com.doo.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;

/**
 * Manages SSE connections for live task updates.
 * Each task can have multiple subscribers (multiple browser tabs).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskEventPublisher {

    private final ObjectMapper objectMapper;

    // taskId -> list of active SSE emitters
    private final ConcurrentHashMap<UUID, CopyOnWriteArrayList<SseEmitter>> emitters = new ConcurrentHashMap<>();

    public SseEmitter subscribe(UUID taskId) {
        SseEmitter emitter = new SseEmitter(300_000L); // 5 min timeout

        emitters.computeIfAbsent(taskId, k -> new CopyOnWriteArrayList<>()).add(emitter);

        emitter.onCompletion(() -> removeEmitter(taskId, emitter));
        emitter.onTimeout(() -> removeEmitter(taskId, emitter));
        emitter.onError(e -> removeEmitter(taskId, emitter));

        // Send initial connected event
        sendToEmitter(emitter, TaskEvent.connected(taskId));

        log.debug("SSE subscriber added for task {}", taskId);
        return emitter;
    }

    public void publish(TaskEvent event) {
        List<SseEmitter> taskEmitters = emitters.get(event.taskId());
        if (taskEmitters == null || taskEmitters.isEmpty()) return;

        List<SseEmitter> deadEmitters = new CopyOnWriteArrayList<>();
        for (SseEmitter emitter : taskEmitters) {
            if (!sendToEmitter(emitter, event)) {
                deadEmitters.add(emitter);
            }
        }
        taskEmitters.removeAll(deadEmitters);

        if ("completed".equalsIgnoreCase(event.eventType()) || "error".equalsIgnoreCase(event.eventType())
            || "COMPLETED".equalsIgnoreCase(event.status()) || "FAILED".equalsIgnoreCase(event.status())) {
            for (SseEmitter emitter : taskEmitters) {
                try {
                    emitter.complete();
                } catch (Exception ignored) {}
            }
            emitters.remove(event.taskId());
        }
    }

    private boolean sendToEmitter(SseEmitter emitter, TaskEvent event) {
        try {
            String data = objectMapper.writeValueAsString(event);
            emitter.send(SseEmitter.event()
                .name(event.eventType())
                .data(data));
            return true;
        } catch (IOException e) {
            log.debug("SSE emitter closed or error: {}", e.getMessage());
            return false;
        }
    }

    private void removeEmitter(UUID taskId, SseEmitter emitter) {
        List<SseEmitter> taskEmitters = emitters.get(taskId);
        if (taskEmitters != null) {
            taskEmitters.remove(emitter);
        }
    }

    public record TaskEvent(
        UUID taskId,
        String eventType,
        String status,
        String message,
        Map<String, Object> data,
        Instant timestamp
    ) {
        public static TaskEvent connected(UUID taskId) {
            return new TaskEvent(taskId, "connected", null, "Connected to task stream", Map.of(), Instant.now());
        }

        public static TaskEvent statusChange(UUID taskId, String status, String message) {
            return new TaskEvent(taskId, "status_change", status, message, Map.of(), Instant.now());
        }

        public static TaskEvent stepUpdate(UUID taskId, String status, String message, int step, Map<String, Object> data) {
            return new TaskEvent(taskId, "step_update", status, message, 
                data != null ? data : Map.of("step", step), Instant.now());
        }

        public static TaskEvent screenshot(UUID taskId, String label, String base64Image) {
            return new TaskEvent(taskId, "screenshot", null, label,
                Map.of("image", base64Image, "label", label), Instant.now());
        }

        public static TaskEvent approvalRequired(UUID taskId, UUID approvalId, String prompt, String proposedAction) {
            return new TaskEvent(taskId, "approval_required", "WAITING_FOR_USER", prompt,
                Map.of("approvalId", approvalId.toString(), "proposedAction", proposedAction != null ? proposedAction : ""), Instant.now());
        }

        public static TaskEvent error(UUID taskId, String message) {
            return new TaskEvent(taskId, "error", "FAILED", message, Map.of(), Instant.now());
        }

        public static TaskEvent completed(UUID taskId, String summary) {
            return new TaskEvent(taskId, "completed", "COMPLETED", summary, Map.of(), Instant.now());
        }

        public static TaskEvent debug(UUID taskId, String message, Map<String, Object> data) {
            return new TaskEvent(taskId, "debug", null, message, data != null ? data : Map.of(), Instant.now());
        }

        /** Carries task field updates (workflowType, etc.) to the frontend without a status change. */
        public static TaskEvent taskUpdated(UUID taskId, String workflowType) {
            return new TaskEvent(taskId, "task_updated", null, "Task classified",
                Map.of("workflowType", workflowType != null ? workflowType : "UNKNOWN"), Instant.now());
        }
    }
}
