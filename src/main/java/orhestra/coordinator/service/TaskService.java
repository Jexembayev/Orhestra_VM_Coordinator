package orhestra.coordinator.service;

import orhestra.coordinator.api.internal.v1.dto.ClaimTasksResponse;
import orhestra.coordinator.config.CoordinatorConfig;
import orhestra.coordinator.model.*;
import orhestra.coordinator.repository.SpotRepository;
import orhestra.coordinator.repository.TaskRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Service layer for task operations.
 * Contains business logic for task claiming, completion, and lifecycle
 * management.
 */
public class TaskService {

    private static final Logger log = LoggerFactory.getLogger(TaskService.class);

    private final TaskRepository taskRepository;
    private final SpotRepository spotRepository;
    private final SpotTaskBlacklist blacklist;
    private final CoordinatorConfig config;
    private static final ObjectMapper mapper = new ObjectMapper();

    public TaskService(TaskRepository taskRepository, CoordinatorConfig config) {
        this(taskRepository, null, null, config);
    }

    public TaskService(TaskRepository taskRepository, SpotRepository spotRepository,
            SpotTaskBlacklist blacklist, CoordinatorConfig config) {
        this.taskRepository = taskRepository;
        this.spotRepository = spotRepository;
        this.blacklist = blacklist;
        this.config = config;
    }

    /**
     * Claim tasks for a SPOT node.
     * Atomically assigns up to maxTasks to the SPOT.
     */
    public List<Task> claimTasks(String spotId, int maxTasks) {
        if (spotId == null || spotId.isBlank()) {
            throw new IllegalArgumentException("spotId is required");
        }
        if (maxTasks <= 0) {
            throw new IllegalArgumentException("maxTasks must be positive");
        }

        int limit = Math.min(maxTasks, 10);

        // Try capability-aware claim if spot repository is available
        if (spotRepository != null) {
            Optional<Spot> spotOpt = spotRepository.findById(spotId);
            if (spotOpt.isPresent()) {
                Spot spot = spotOpt.get();
                String capJson = spot.capabilitiesJson();
                if (capJson != null && !capJson.isBlank()) {
                    try {
                        SpotCapabilities caps = mapper.readValue(capJson, SpotCapabilities.class);
                        List<String> optIds = caps.optimizerIds();
                        List<String> algs = caps.allAlgorithms();

                        // Get blacklisted task IDs for this spot
                        List<String> excluded = blacklist != null
                                ? taskRepository.findByStatus(TaskStatus.NEW, 1000).stream()
                                        .map(Task::id)
                                        .filter(tid -> blacklist.isBlacklisted(spotId, tid))
                                        .toList()
                                : Collections.emptyList();

                        return taskRepository.claimTasks(spotId, limit, optIds, algs, excluded);
                    } catch (Exception e) {
                        log.warn("Failed to parse capabilities for spot {}, falling back to unfiltered", spotId, e);
                    }
                }
            }
        }

        // Fallback: unfiltered claim
        return taskRepository.claimTasks(spotId, limit);
    }

    /**
     * Complete a task successfully.
     * 
     * @return true if completed, false if not found or not assigned to spot
     */
    public boolean completeTask(String taskId, String spotId, long runtimeMs, Integer iter, Double fopt,
            String result) {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId is required");
        }
        if (spotId == null || spotId.isBlank()) {
            throw new IllegalArgumentException("spotId is required");
        }

        boolean completed = taskRepository.complete(taskId, spotId, runtimeMs, iter, fopt, result);

        if (completed) {
            log.info("Task {} completed by spot {} in {}ms", taskId, spotId, runtimeMs);
        } else {
            log.warn("Failed to complete task {} by spot {} - not found or not assigned", taskId, spotId);
        }

        return completed;
    }

    /**
     * Complete a task successfully with idempotent result.
     * Returns detailed result for HTTP response handling.
     */
    public TaskCompleteResult completeTaskIdempotent(String taskId, String spotId, long runtimeMs, Integer iter,
            Double fopt, String result, Long peakRamMb) {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId is required");
        }
        if (spotId == null || spotId.isBlank()) {
            throw new IllegalArgumentException("spotId is required");
        }

        TaskCompleteResult res = taskRepository.completeIdempotent(taskId, spotId, runtimeMs, iter, fopt, result, peakRamMb);

        if (res == TaskCompleteResult.COMPLETED) {
            log.info("Task {} completed by spot {} in {}ms", taskId, spotId, runtimeMs);
        } else if (res == TaskCompleteResult.ALREADY_DONE) {
            log.debug("Task {} already complete (idempotent)", taskId);
        } else {
            log.warn("Failed to complete task {} by spot {} - result: {}", taskId, spotId, res);
        }

        return res;
    }

    /**
     * Report task failure.
     * 
     * @return true if task will be retried, false if permanently failed
     */
    public boolean failTask(String taskId, String spotId, String errorMessage, boolean retriable) {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId is required");
        }
        if (spotId == null || spotId.isBlank()) {
            throw new IllegalArgumentException("spotId is required");
        }

        return taskRepository.fail(taskId, spotId, errorMessage, retriable);
    }

    /**
     * Report task failure with explicit failure reason.
     * Handles failure taxonomy: UNSUPPORTED, MISSING_ARTIFACT, RUNTIME_ERROR,
     * TIMEOUT.
     */
    public boolean failTaskWithReason(String taskId, String spotId, String errorMessage,
            FailureReason reason) {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId is required");
        }
        if (spotId == null || spotId.isBlank()) {
            throw new IllegalArgumentException("spotId is required");
        }

        if (reason == FailureReason.UNSUPPORTED) {
            // Don't count as attempt, re-queue for another spot
            if (blacklist != null)
                blacklist.blacklist(spotId, taskId);
            log.info("Task {} UNSUPPORTED by spot {}, blacklisted", taskId, spotId);
            // Re-queue: set back to NEW without incrementing attempt
            taskRepository.resetToNew(taskId);
            return true; // task will be retried
        }

        if (reason == FailureReason.MISSING_ARTIFACT) {
            // Re-queue task, but also log degraded spot
            if (blacklist != null)
                blacklist.blacklist(spotId, taskId);
            log.warn("Task {} MISSING_ARTIFACT on spot {}, spot degraded", taskId, spotId);
            taskRepository.resetToNew(taskId);
            return true;
        }

        // RUNTIME_ERROR or TIMEOUT: normal retry with attempt counting
        return taskRepository.fail(taskId, spotId, errorMessage, reason.countsAsAttempt());
    }

    /**
     * Report task failure with idempotent result.
     * Returns detailed result for HTTP response handling.
     */
    public TaskFailResult failTaskIdempotent(String taskId, String spotId, String errorMessage, boolean retriable,
            Integer exitCode, String outputSnippet) {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId is required");
        }
        if (spotId == null || spotId.isBlank()) {
            throw new IllegalArgumentException("spotId is required");
        }

        TaskFailResult res = taskRepository.failIdempotent(taskId, spotId, errorMessage, retriable, exitCode, outputSnippet);

        if (res == TaskFailResult.RETRIED) {
            log.info("Task {} failed by spot {}, will retry", taskId, spotId);
        } else if (res == TaskFailResult.FAILED) {
            log.info("Task {} permanently failed", taskId);
        } else if (res == TaskFailResult.ALREADY_TERMINAL) {
            log.debug("Task {} already terminal (idempotent)", taskId);
        } else {
            log.warn("Failed to report failure for task {} by spot {} - result: {}", taskId, spotId, res);
        }

        return res;
    }

    /**
     * Find a task by ID.
     */
    public Optional<Task> findById(String taskId) {
        return taskRepository.findById(taskId);
    }

    /**
     * Find all tasks for a job.
     */
    public List<Task> findByJobId(String jobId) {
        return taskRepository.findByJobId(jobId);
    }

    /**
     * Get recent tasks for UI display.
     */
    public List<Task> findRecent(int limit) {
        return taskRepository.findRecent(limit);
    }

    /**
     * Find tasks by status.
     */
    public List<Task> findByStatus(TaskStatus status, int limit) {
        return taskRepository.findByStatus(status, limit);
    }

    /**
     * Create multiple tasks for a job.
     */
    public void createTasks(List<Task> tasks) {
        taskRepository.saveAll(tasks);
        log.info("Created {} tasks", tasks.size());
    }

    /**
     * Generate a new task ID.
     */
    public String generateTaskId() {
        return "task-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * Count pending (NEW) tasks.
     */
    public int countPending() {
        return taskRepository.findByStatus(TaskStatus.NEW, Integer.MAX_VALUE).size();
    }

    /**
     * Count running tasks.
     */
    public int countRunning() {
        return taskRepository.findByStatus(TaskStatus.RUNNING, Integer.MAX_VALUE).size();
    }

    /**
     * Find all RUNNING tasks currently assigned to a specific SPOT.
     */
    public List<Task> findRunningForSpot(String spotId) {
        return taskRepository.findRunningBySpotId(spotId);
    }

    /**
     * Free all tasks assigned to a SPOT (when SPOT goes offline).
     */
    public int freeTasksForSpot(String spotId) {
        int freed = taskRepository.freeTasksForSpot(spotId);
        if (freed > 0) {
            log.info("Freed {} tasks from offline spot {}", freed, spotId);
        }
        return freed;
    }
}
