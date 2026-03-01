package orhestra.coordinator.api.internal.v1;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import orhestra.coordinator.api.Controller;
import orhestra.coordinator.api.internal.v1.dto.*;
import orhestra.coordinator.core.AppBus;
import orhestra.coordinator.model.Task;
import orhestra.coordinator.model.TaskCompleteResult;
import orhestra.coordinator.model.TaskFailResult;
import orhestra.coordinator.server.RouterHandler;
import orhestra.coordinator.service.TaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Controller for task operations (internal API).
 * POST /internal/v1/tasks/claim - Claim tasks for execution
 * POST /internal/v1/tasks/{taskId}/complete - Report task completion
 * (idempotent)
 * POST /internal/v1/tasks/{taskId}/fail - Report task failure (idempotent)
 */
public class TaskController implements Controller {

    private static final Logger log = LoggerFactory.getLogger(TaskController.class);

    // Patterns for path matching
    private static final Pattern CLAIM_PATTERN = Pattern.compile("^/internal/v1/tasks/claim$");
    private static final Pattern COMPLETE_PATTERN = Pattern.compile("^/internal/v1/tasks/([^/]+)/complete$");
    private static final Pattern FAIL_PATTERN = Pattern.compile("^/internal/v1/tasks/([^/]+)/fail$");

    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    @Override
    public boolean matches(HttpMethod method, String path) {
        if (!method.equals(HttpMethod.POST)) {
            return false;
        }
        return CLAIM_PATTERN.matcher(path).matches()
                || COMPLETE_PATTERN.matcher(path).matches()
                || FAIL_PATTERN.matcher(path).matches();
    }

    @Override
    public ControllerResponse handle(ChannelHandlerContext ctx, FullHttpRequest req, String path) {
        // Let exceptions bubble to RouterHandler which exposes full error details
        // Only catch validation errors to return proper 400 responses
        try {
            if (CLAIM_PATTERN.matcher(path).matches()) {
                return handleClaim(req);
            }

            Matcher completeMatcher = COMPLETE_PATTERN.matcher(path);
            if (completeMatcher.matches()) {
                String taskId = completeMatcher.group(1);
                return handleComplete(req, taskId);
            }

            Matcher failMatcher = FAIL_PATTERN.matcher(path);
            if (failMatcher.matches()) {
                String taskId = failMatcher.group(1);
                return handleFail(req, taskId);
            }

            return ControllerResponse.notFound("unknown task endpoint");

        } catch (IllegalArgumentException e) {
            return ControllerResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            // Rethrow as RuntimeException so RouterHandler can expose error details
            throw new RuntimeException("Task controller error: " + e.getMessage(), e);
        }
    }

    /**
     * POST /internal/v1/tasks/claim - Claim tasks for execution
     */
    private ControllerResponse handleClaim(FullHttpRequest req) throws Exception {
        String body = req.content().toString(StandardCharsets.UTF_8);
        ClaimTasksRequest request = RouterHandler.mapper().readValue(body, ClaimTasksRequest.class);

        // Validate
        request.validate();

        // Claim tasks
        List<Task> claimed = taskService.claimTasks(request.spotId(), request.maxTasks());

        // Fire UI event
        if (!claimed.isEmpty()) {
            AppBus.fireTasksChanged();
        }

        ClaimTasksResponse response = ClaimTasksResponse.from(claimed);
        return ControllerResponse.json(RouterHandler.mapper().writeValueAsString(response));
    }

    /**
     * POST /internal/v1/tasks/{taskId}/complete - Report task completion
     * (idempotent)
     */
    private ControllerResponse handleComplete(FullHttpRequest req, String taskId) throws Exception {
        String body = req.content().toString(StandardCharsets.UTF_8);
        TaskCompleteRequest request = RouterHandler.mapper().readValue(body, TaskCompleteRequest.class);

        // Validate
        request.validate();

        // Complete the task with idempotent handling
        TaskCompleteResult result = taskService.completeTaskIdempotent(
                taskId,
                request.spotId(),
                request.runtimeMs(),
                request.iter(),
                request.fopt(),
                request.resultJson());

        // Fire UI event on success
        if (result == TaskCompleteResult.COMPLETED) {
            AppBus.fireTasksChanged();
        }

        // Return appropriate HTTP response
        return switch (result) {
            case COMPLETED -> ControllerResponse.json(
                    RouterHandler.mapper().writeValueAsString(Map.of("success", true)));
            case ALREADY_DONE -> ControllerResponse.json(
                    RouterHandler.mapper().writeValueAsString(Map.of("success", true, "message", "already completed")));
            case NOT_FOUND -> ControllerResponse.json(HttpResponseStatus.NOT_FOUND,
                    RouterHandler.mapper().writeValueAsString(Map.of("success", false, "error", "task not found")));
            case WRONG_SPOT -> ControllerResponse.json(HttpResponseStatus.CONFLICT,
                    RouterHandler.mapper()
                            .writeValueAsString(Map.of("success", false, "error", "task not assigned to this spot")));
        };
    }

    /**
     * POST /internal/v1/tasks/{taskId}/fail - Report task failure (idempotent)
     */
    private ControllerResponse handleFail(FullHttpRequest req, String taskId) throws Exception {
        String body = req.content().toString(StandardCharsets.UTF_8);
        TaskFailRequest request = RouterHandler.mapper().readValue(body, TaskFailRequest.class);

        // Validate
        request.validate();

        // Check for structured failure reason
        if (request.failureReason() != null && !request.failureReason().isBlank()) {
            orhestra.coordinator.model.FailureReason reason = orhestra.coordinator.model.FailureReason
                    .fromString(request.failureReason());

            boolean willRetry = taskService.failTaskWithReason(taskId, request.spotId(), request.error(), reason);

            AppBus.fireTasksChanged();

            return ControllerResponse.json(
                    RouterHandler.mapper().writeValueAsString(Map.of(
                            "success", true,
                            "willRetry", willRetry,
                            "failureReason", reason.name())));
        }

        // Legacy: use idempotent handling
        TaskFailResult result = taskService.failTaskIdempotent(
                taskId,
                request.spotId(),
                request.error(),
                request.retriable());

        // Fire UI event on status change
        if (result == TaskFailResult.RETRIED || result == TaskFailResult.FAILED) {
            AppBus.fireTasksChanged();
        }

        // Return appropriate HTTP response
        return switch (result) {
            case RETRIED -> ControllerResponse.json(
                    RouterHandler.mapper().writeValueAsString(Map.of("success", true, "willRetry", true)));
            case FAILED -> ControllerResponse.json(
                    RouterHandler.mapper().writeValueAsString(Map.of("success", true, "willRetry", false)));
            case ALREADY_TERMINAL -> ControllerResponse.json(
                    RouterHandler.mapper().writeValueAsString(Map.of("success", true, "message", "already terminal")));
            case NOT_FOUND -> ControllerResponse.json(HttpResponseStatus.NOT_FOUND,
                    RouterHandler.mapper().writeValueAsString(Map.of("success", false, "error", "task not found")));
            case WRONG_SPOT -> ControllerResponse.json(HttpResponseStatus.CONFLICT,
                    RouterHandler.mapper()
                            .writeValueAsString(Map.of("success", false, "error", "task not assigned to this spot")));
        };
    }
}
