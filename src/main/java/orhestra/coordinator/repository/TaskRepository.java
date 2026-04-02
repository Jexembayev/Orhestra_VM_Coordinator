package orhestra.coordinator.repository;

import orhestra.coordinator.model.Task;
import orhestra.coordinator.model.TaskCompleteResult;
import orhestra.coordinator.model.TaskFailResult;
import orhestra.coordinator.model.TaskStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Repository interface for Task persistence.
 * Implementations can use JDBC, JPA, or in-memory storage.
 */
public interface TaskRepository {

    /**
     * Save a new task.
     * 
     * @param task the task to save
     */
    void save(Task task);

    /**
     * Save multiple tasks in a batch.
     * 
     * @param tasks the tasks to save
     */
    void saveAll(List<Task> tasks);

    /**
     * Find a task by ID.
     * 
     * @param taskId the task ID
     * @return the task if found
     */
    Optional<Task> findById(String taskId);

    /**
     * Find all tasks for a job.
     * 
     * @param jobId the job ID
     * @return list of tasks
     */
    List<Task> findByJobId(String jobId);

    /**
     * Find tasks by status.
     * 
     * @param status the status to filter by
     * @param limit  maximum number of results
     * @return list of tasks
     */
    List<Task> findByStatus(TaskStatus status, int limit);

    /**
     * Atomically claim up to N tasks for a SPOT.
     * Moves tasks from NEW to RUNNING and assigns them.
     * 
     * @param spotId   the SPOT claiming the tasks
     * @param maxTasks maximum tasks to claim
     * @return list of claimed tasks
     */
    List<Task> claimTasks(String spotId, int maxTasks);

    /**
     * Atomically claim tasks matching spot capabilities.
     * Only claims tasks whose optimizer_id and algorithm are in the supported
     * lists.
     * Tasks whose IDs are in excludeTaskIds are skipped (blacklisted).
     *
     * @param spotId          the SPOT claiming the tasks
     * @param maxTasks        maximum tasks to claim
     * @param supportedOptIds optimizer IDs this spot supports (null = no filter)
     * @param supportedAlgs   algorithms this spot supports (null = no filter)
     * @param excludeTaskIds  task IDs to exclude (blacklisted)
     * @return list of claimed tasks
     */
    List<Task> claimTasks(String spotId, int maxTasks, List<String> supportedOptIds,
            List<String> supportedAlgs, List<String> excludeTaskIds);

    /**
     * Complete a task successfully.
     * Only succeeds if task is RUNNING and assigned to the given SPOT.
     * 
     * @param taskId    the task ID
     * @param spotId    the SPOT completing the task (for verification)
     * @param runtimeMs execution time in milliseconds
     * @param iter      iterations performed
     * @param fopt      optimal function value
     * @param result    full result JSON
     * @return true if updated, false if task not found or not assigned to spot
     */
    boolean complete(String taskId, String spotId, long runtimeMs, Integer iter, Double fopt, String result);

    /**
     * Mark a task as failed.
     * If retriable and attempts < maxAttempts, moves back to NEW.
     * Otherwise, marks as FAILED.
     * 
     * @param taskId       the task ID
     * @param spotId       the SPOT reporting failure (for verification)
     * @param errorMessage the error message
     * @param retriable    whether the failure is retriable
     * @return true if task will be retried, false if permanently failed
     */
    boolean fail(String taskId, String spotId, String errorMessage, boolean retriable);

    /**
     * Find tasks that have been RUNNING for too long (stuck).
     * Used by the reaper to recover orphaned tasks.
     * 
     * @param startedBefore tasks started before this timestamp are considered stuck
     * @return list of stuck tasks
     */
    List<Task> findStuckRunning(Instant startedBefore);

    /**
     * Reset a task back to NEW status for retry.
     * 
     * @param taskId the task ID
     * @return true if reset, false if not found
     */
    boolean resetToNew(String taskId);

    /**
     * Mark a task as permanently failed.
     * 
     * @param taskId       the task ID
     * @param errorMessage the error message
     * @return true if updated
     */
    boolean markFailed(String taskId, String errorMessage);

    /**
     * Free all RUNNING tasks assigned to a SPOT.
     * Used when a SPOT goes offline.
     * 
     * @param spotId the SPOT ID
     * @return number of tasks freed
     */
    int freeTasksForSpot(String spotId);

    /**
     * Count tasks by status for a job.
     * 
     * @param jobId  the job ID
     * @param status the status
     * @return count
     */
    int countByJobIdAndStatus(String jobId, TaskStatus status);

    /**
     * Get recent tasks for UI display.
     * 
     * @param limit maximum results
     * @return tasks ordered by recency
     */
    List<Task> findRecent(int limit);

    /**
     * Idempotent complete: returns detailed result for proper HTTP responses.
     * 
     * @param taskId    the task ID
     * @param spotId    the SPOT completing the task
     * @param runtimeMs execution time in milliseconds
     * @param iter      iterations performed
     * @param fopt      optimal function value
     * @param result    full result JSON
     * @return detailed result indicating outcome
     */
    TaskCompleteResult completeIdempotent(String taskId, String spotId, long runtimeMs, Integer iter, Double fopt,
            String result, Long peakRamMb);

    /**
     * Idempotent fail: returns detailed result for proper HTTP responses.
     * 
     * @param taskId       the task ID
     * @param spotId       the SPOT reporting failure
     * @param errorMessage the error message
     * @param retriable    whether the failure is retriable
     * @return detailed result indicating outcome
     */
    TaskFailResult failIdempotent(String taskId, String spotId, String errorMessage, boolean retriable,
            Integer exitCode, String outputSnippet);

    /**
     * Update the status of a task.
     */
    boolean updateStatus(String taskId, TaskStatus status);

    /**
     * Find all RUNNING tasks currently assigned to a specific SPOT.
     *
     * @param spotId the SPOT ID
     * @return list of running tasks
     */
    List<Task> findRunningBySpotId(String spotId);
}
