package com.doo;

import com.doo.api.TaskController;
import com.doo.domain.Task;
import com.doo.domain.TaskStatus;
import com.doo.domain.WorkflowType;
import com.doo.service.TaskService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(TaskController.class)
@DisplayName("Task REST API Integration Tests")
class TaskApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private TaskService taskService;

    @MockBean
    private com.doo.events.TaskEventPublisher taskEventPublisher;

    @MockBean
    private com.doo.repository.ApprovalRequestRepository approvalRepository;

    @Test
    @DisplayName("POST /api/tasks creates a task and returns 201 CREATED")
    void createTask_returns201() throws Exception {
        UUID taskId = UUID.randomUUID();
        Task mockTask = Task.builder()
            .id(taskId)
            .userRequest("Search the web for AI tools")
            .status(TaskStatus.QUEUED)
            .workflowType(WorkflowType.WEB_RESEARCH)
            .createdAt(Instant.now())
            .updatedAt(Instant.now())
            .build();

        given(taskService.createTask(anyString(), any())).willReturn(mockTask);

        mockMvc.perform(post("/api/tasks")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("userRequest", "Search the web for AI tools"))))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").value(taskId.toString()))
            .andExpect(jsonPath("$.userRequest").value("Search the web for AI tools"))
            .andExpect(jsonPath("$.status").value("QUEUED"))
            .andExpect(jsonPath("$.workflowType").value("WEB_RESEARCH"));
    }

    @Test
    @DisplayName("GET /api/tasks/{id} returns the task with 200 OK")
    void getTask_returns200() throws Exception {
        UUID taskId = UUID.randomUUID();
        Task mockTask = Task.builder()
            .id(taskId)
            .userRequest("Compare products")
            .status(TaskStatus.RUNNING)
            .workflowType(WorkflowType.PRODUCT_COMPARISON)
            .currentStep(2)
            .createdAt(Instant.now())
            .updatedAt(Instant.now())
            .build();

        given(taskService.getTask(taskId)).willReturn(mockTask);

        mockMvc.perform(get("/api/tasks/" + taskId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(taskId.toString()))
            .andExpect(jsonPath("$.status").value("RUNNING"))
            .andExpect(jsonPath("$.currentStep").value(2));
    }

    @Test
    @DisplayName("POST /api/tasks/{id}/cancel marks task as CANCELLED")
    void cancelTask_returns200() throws Exception {
        UUID taskId = UUID.randomUUID();
        Task mockTask = Task.builder()
            .id(taskId)
            .userRequest("Long task")
            .status(TaskStatus.CANCELLED)
            .workflowType(WorkflowType.UI_INTERACTION)
            .createdAt(Instant.now())
            .updatedAt(Instant.now())
            .build();

        given(taskService.cancelTask(taskId)).willReturn(mockTask);

        mockMvc.perform(post("/api/tasks/" + taskId + "/cancel"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(taskId.toString()))
            .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    @DisplayName("GET /api/tasks returns paginated task list")
    void listTasks_returnsPaginatedList() throws Exception {
        UUID taskId = UUID.randomUUID();
        Task mockTask = Task.builder()
            .id(taskId)
            .userRequest("Sample task")
            .status(TaskStatus.COMPLETED)
            .workflowType(WorkflowType.WEB_RESEARCH)
            .createdAt(Instant.now())
            .updatedAt(Instant.now())
            .build();

        given(taskService.getTaskHistory(anyInt(), anyInt()))
            .willReturn(new PageImpl<>(List.of(mockTask)));

        mockMvc.perform(get("/api/tasks?page=0&size=10"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items").isArray())
            .andExpect(jsonPath("$.items[0].id").value(taskId.toString()))
            .andExpect(jsonPath("$.total").value(1));
    }

    @Test
    @DisplayName("POST /api/tasks fails with 400 Bad Request if userRequest is blank")
    void createTask_blankRequest_returns400() throws Exception {
        mockMvc.perform(post("/api/tasks")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("userRequest", "   "))))
            .andExpect(status().isBadRequest());
    }
}
