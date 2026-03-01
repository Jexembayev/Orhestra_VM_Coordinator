package orhestra.coordinator.store;

import orhestra.coordinator.model.Task;
import orhestra.coordinator.model.TaskCompleteResult;
import orhestra.coordinator.model.TaskFailResult;
import orhestra.coordinator.model.TaskStatus;
import orhestra.coordinator.repository.TaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * JDBC implementation of TaskRepository.
 * Uses pessimistic locking for atomic task claiming.
 */
public class JdbcTaskRepository implements TaskRepository {

    private static final Logger log = LoggerFactory.getLogger(JdbcTaskRepository.class);

    private final Database db;

    public JdbcTaskRepository(Database db) {
        this.db = db;
    }

    @Override
    public void save(Task task) {
        String sql = """
                    INSERT INTO tasks (id, job_id, payload, status, assigned_to, priority, attempts, max_attempts,
                                       error_message, created_at, started_at, finished_at, runtime_ms, iter, fopt, result,
                                       algorithm, input_iterations, input_agents, input_dimension)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;

        try (Connection conn = db.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, task.id());
            ps.setString(2, task.jobId());
            ps.setString(3, task.payload());
            ps.setString(4, task.status().name());
            ps.setString(5, task.assignedTo());
            ps.setInt(6, task.priority());
            ps.setInt(7, task.attempts());
            ps.setInt(8, task.maxAttempts());
            ps.setString(9, task.errorMessage());
            setTimestamp(ps, 10, task.createdAt());
            setTimestamp(ps, 11, task.startedAt());
            setTimestamp(ps, 12, task.finishedAt());
            setLongOrNull(ps, 13, task.runtimeMs());
            setIntOrNull(ps, 14, task.iter());
            setDoubleOrNull(ps, 15, task.fopt());
            ps.setString(16, task.result());
            ps.setString(17, task.algorithm());
            setIntOrNull(ps, 18, task.inputIterations());
            setIntOrNull(ps, 19, task.inputAgents());
            setIntOrNull(ps, 20, task.inputDimension());

            ps.executeUpdate();
            conn.commit();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save task: " + task.id(), e);
        }
    }

    @Override
    public void saveAll(List<Task> tasks) {
        if (tasks.isEmpty())
            return;

        String sql = """
                    INSERT INTO tasks (id, job_id, payload, status, priority, attempts, max_attempts, created_at,
                                       algorithm, optimizer_id, function, input_iterations, input_agents, input_dimension)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;

        try (Connection conn = db.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {

            for (Task task : tasks) {
                ps.setString(1, task.id());
                ps.setString(2, task.jobId());
                ps.setString(3, task.payload());
                ps.setString(4, task.status().name());
                ps.setInt(5, task.priority());
                ps.setInt(6, task.attempts());
                ps.setInt(7, task.maxAttempts());
                setTimestamp(ps, 8, task.createdAt() != null ? task.createdAt() : Instant.now());
                ps.setString(9, task.algorithm());
                ps.setString(10, task.optimizerId());
                ps.setString(11, task.function());
                setIntOrNull(ps, 12, task.inputIterations());
                setIntOrNull(ps, 13, task.inputAgents());
                setIntOrNull(ps, 14, task.inputDimension());
                ps.addBatch();
            }

            ps.executeBatch();
            conn.commit();

            log.debug("Saved {} tasks in batch", tasks.size());
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save tasks batch", e);
        }
    }

    @Override
    public Optional<Task> findById(String taskId) {
        String sql = "SELECT * FROM tasks WHERE id = ?";

        try (Connection conn = db.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, taskId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
            }
            return Optional.empty();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find task: " + taskId, e);
        }
    }

    @Override
    public List<Task> findByJobId(String jobId) {
        String sql = "SELECT * FROM tasks WHERE job_id = ? ORDER BY created_at";

        try (Connection conn = db.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, jobId);
            return executeQuery(ps);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find tasks for job: " + jobId, e);
        }
    }

    @Override
    public List<Task> findByStatus(TaskStatus status, int limit) {
        String sql = "SELECT * FROM tasks WHERE status = ? ORDER BY priority DESC, created_at LIMIT ?";

        try (Connection conn = db.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, status.name());
            ps.setInt(2, limit);
            return executeQuery(ps);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find tasks by status: " + status, e);
        }
    }

    @Override
    public List<Task> claimTasks(String spotId, int maxTasks) {
        // Use SELECT FOR UPDATE to lock rows, then update them
        // Include job_id so we can return it to the SPOT
        String selectSql = """
                    SELECT id, job_id, payload FROM tasks
                    WHERE status = 'NEW'
                    ORDER BY priority DESC, created_at
                    LIMIT ?
                    FOR UPDATE
                """;

        // IMPORTANT: Keep WHERE status='NEW' for atomicity - ensures we never
        // re-claim a task that was already transitioned by another process
        String updateSql = """
                    UPDATE tasks
                    SET status = 'RUNNING', assigned_to = ?, started_at = ?, attempts = attempts + 1
                    WHERE id = ? AND status = 'NEW'
                """;

        List<Task> claimed = new ArrayList<>();

        try (Connection conn = db.getConnection()) {
            try (PreparedStatement selectPs = conn.prepareStatement(selectSql);
                    PreparedStatement updatePs = conn.prepareStatement(updateSql)) {

                selectPs.setInt(1, maxTasks);

                try (ResultSet rs = selectPs.executeQuery()) {
                    Timestamp now = Timestamp.from(Instant.now());

                    while (rs.next()) {
                        String id = rs.getString("id");
                        String jobId = rs.getString("job_id");
                        String payload = rs.getString("payload");

                        updatePs.setString(1, spotId);
                        updatePs.setTimestamp(2, now);
                        updatePs.setString(3, id);
                        updatePs.addBatch();

                        // Build claimed task with jobId for response
                        claimed.add(Task.builder()
                                .id(id)
                                .jobId(jobId)
                                .payload(payload)
                                .status(TaskStatus.RUNNING)
                                .assignedTo(spotId)
                                .startedAt(now.toInstant())
                                .build());
                    }
                }

                if (!claimed.isEmpty()) {
                    int[] results = updatePs.executeBatch();
                    // Log how many rows were actually updated (should match claimed.size())
                    int actualUpdates = 0;
                    for (int r : results) {
                        if (r > 0)
                            actualUpdates++;
                    }
                    if (actualUpdates != claimed.size()) {
                        log.warn("Claim atomicity: selected {} tasks but only updated {} (race condition)",
                                claimed.size(), actualUpdates);
                    }
                }

                conn.commit();

                if (!claimed.isEmpty()) {
                    log.info("Claimed {} tasks for spot {}", claimed.size(), spotId);
                }

                return claimed;
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to claim tasks for spot: " + spotId, e);
        }
    }

    @Override
    public List<Task> claimTasks(String spotId, int maxTasks, List<String> supportedOptIds,
            List<String> supportedAlgs, List<String> excludeTaskIds) {
        // Build dynamic SQL with capability filters
        StringBuilder selectSql = new StringBuilder(
                "SELECT id, job_id, payload FROM tasks WHERE status = 'NEW'");
        List<Object> params = new ArrayList<>();

        // Filter by optimizer_id if spot declares supported optimizers
        if (supportedOptIds != null && !supportedOptIds.isEmpty()) {
            selectSql.append(" AND (optimizer_id IS NULL OR optimizer_id IN (");
            selectSql.append(String.join(",", supportedOptIds.stream().map(s -> "?").toList()));
            selectSql.append("))");
            params.addAll(supportedOptIds);
        }

        // Filter by algorithm if spot declares supported algorithms
        if (supportedAlgs != null && !supportedAlgs.isEmpty()) {
            selectSql.append(" AND (algorithm IS NULL OR algorithm IN (");
            selectSql.append(String.join(",", supportedAlgs.stream().map(s -> "?").toList()));
            selectSql.append("))");
            params.addAll(supportedAlgs);
        }

        // Exclude blacklisted task IDs
        if (excludeTaskIds != null && !excludeTaskIds.isEmpty()) {
            selectSql.append(" AND id NOT IN (");
            selectSql.append(String.join(",", excludeTaskIds.stream().map(s -> "?").toList()));
            selectSql.append(")");
            params.addAll(excludeTaskIds);
        }

        selectSql.append(" ORDER BY priority DESC, created_at LIMIT ? FOR UPDATE");
        params.add(maxTasks);

        String updateSql = """
                    UPDATE tasks
                    SET status = 'RUNNING', assigned_to = ?, started_at = ?, attempts = attempts + 1
                    WHERE id = ? AND status = 'NEW'
                """;

        List<Task> claimed = new ArrayList<>();

        try (Connection conn = db.getConnection()) {
            try (PreparedStatement selectPs = conn.prepareStatement(selectSql.toString());
                    PreparedStatement updatePs = conn.prepareStatement(updateSql)) {

                // Set parameters
                for (int i = 0; i < params.size(); i++) {
                    Object p = params.get(i);
                    if (p instanceof String s)
                        selectPs.setString(i + 1, s);
                    else if (p instanceof Integer n)
                        selectPs.setInt(i + 1, n);
                }

                try (ResultSet rs = selectPs.executeQuery()) {
                    Timestamp now = Timestamp.from(Instant.now());

                    while (rs.next()) {
                        String id = rs.getString("id");
                        String jobId = rs.getString("job_id");
                        String payload = rs.getString("payload");

                        updatePs.setString(1, spotId);
                        updatePs.setTimestamp(2, now);
                        updatePs.setString(3, id);
                        updatePs.addBatch();

                        claimed.add(Task.builder()
                                .id(id)
                                .jobId(jobId)
                                .payload(payload)
                                .status(TaskStatus.RUNNING)
                                .assignedTo(spotId)
                                .startedAt(now.toInstant())
                                .build());
                    }
                }

                if (!claimed.isEmpty()) {
                    updatePs.executeBatch();
                }

                conn.commit();

                if (!claimed.isEmpty()) {
                    log.info("Claimed {} tasks for spot {} (capability-filtered)", claimed.size(), spotId);
                }

                return claimed;
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to claim tasks for spot: " + spotId, e);
        }
    }

    @Override
    public boolean complete(String taskId, String spotId, long runtimeMs, Integer iter, Double fopt, String result) {
        String sql = """
                    UPDATE tasks
                    SET status = 'DONE', finished_at = ?, runtime_ms = ?, iter = ?, fopt = ?, result = ?
                    WHERE id = ? AND assigned_to = ? AND status = 'RUNNING'
                """;

        try (Connection conn = db.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setTimestamp(1, Timestamp.from(Instant.now()));
            ps.setLong(2, runtimeMs);
            setIntOrNull(ps, 3, iter);
            setDoubleOrNull(ps, 4, fopt);
            ps.setString(5, result);
            ps.setString(6, taskId);
            ps.setString(7, spotId);

            int updated = ps.executeUpdate();
            conn.commit();

            if (updated > 0) {
                log.debug("Task {} completed by spot {}", taskId, spotId);
            }

            return updated > 0;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to complete task: " + taskId, e);
        }
    }

    @Override
    public boolean fail(String taskId, String spotId, String errorMessage, boolean retriable) {
        // First, get the task to check attempts
        Optional<Task> taskOpt = findById(taskId);
        if (taskOpt.isEmpty()) {
            return false;
        }

        Task task = taskOpt.get();

        // Verify the spot owns this task
        if (!spotId.equals(task.assignedTo())) {
            log.warn("Spot {} tried to fail task {} but it's assigned to {}", spotId, taskId, task.assignedTo());
            return false;
        }

        if (task.status() != TaskStatus.RUNNING) {
            log.warn("Task {} is not RUNNING, status: {}", taskId, task.status());
            return false;
        }

        boolean willRetry = retriable && task.canRetry();

        if (willRetry) {
            // Reset to NEW for retry
            resetToNew(taskId);
            return true; // willRetry = true
        } else {
            // Mark as permanently failed
            markFailed(taskId, errorMessage);
            return false; // willRetry = false
        }
    }

    @Override
    public List<Task> findStuckRunning(Instant startedBefore) {
        String sql = """
                    SELECT * FROM tasks
                    WHERE status = 'RUNNING' AND started_at < ?
                    ORDER BY started_at
                """;

        try (Connection conn = db.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setTimestamp(1, Timestamp.from(startedBefore));
            return executeQuery(ps);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find stuck tasks", e);
        }
    }

    @Override
    public boolean resetToNew(String taskId) {
        String sql = """
                    UPDATE tasks
                    SET status = 'NEW', assigned_to = NULL, started_at = NULL, error_message = NULL
                    WHERE id = ?
                """;

        try (Connection conn = db.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, taskId);
            int updated = ps.executeUpdate();
            conn.commit();

            if (updated > 0) {
                log.debug("Task {} reset to NEW for retry", taskId);
            }

            return updated > 0;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to reset task: " + taskId, e);
        }
    }

    @Override
    public boolean markFailed(String taskId, String errorMessage) {
        String sql = """
                    UPDATE tasks
                    SET status = 'FAILED', finished_at = ?, error_message = ?
                    WHERE id = ?
                """;

        try (Connection conn = db.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setTimestamp(1, Timestamp.from(Instant.now()));
            ps.setString(2, errorMessage);
            ps.setString(3, taskId);

            int updated = ps.executeUpdate();
            conn.commit();

            if (updated > 0) {
                log.debug("Task {} marked as FAILED: {}", taskId, errorMessage);
            }

            return updated > 0;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to mark task as failed: " + taskId, e);
        }
    }

    @Override
    public int freeTasksForSpot(String spotId) {
        String sql = """
                    UPDATE tasks
                    SET status = 'NEW', assigned_to = NULL, started_at = NULL
                    WHERE assigned_to = ? AND status = 'RUNNING'
                """;

        try (Connection conn = db.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, spotId);
            int freed = ps.executeUpdate();
            conn.commit();

            if (freed > 0) {
                log.info("Freed {} tasks from offline spot {}", freed, spotId);
            }

            return freed;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to free tasks for spot: " + spotId, e);
        }
    }

    @Override
    public int countByJobIdAndStatus(String jobId, TaskStatus status) {
        String sql = "SELECT COUNT(*) FROM tasks WHERE job_id = ? AND status = ?";

        try (Connection conn = db.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, jobId);
            ps.setString(2, status.name());

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
            return 0;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to count tasks", e);
        }
    }

    @Override
    public List<Task> findRecent(int limit) {
        String sql = """
                    SELECT * FROM tasks
                    ORDER BY
                        COALESCE(finished_at, started_at, created_at) DESC
                    LIMIT ?
                """;

        try (Connection conn = db.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setInt(1, limit);
            return executeQuery(ps);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find recent tasks", e);
        }
    }

    @Override
    public TaskCompleteResult completeIdempotent(String taskId, String spotId, long runtimeMs, Integer iter,
            Double fopt, String result) {
        // First check the current state (outside transaction for idempotency checks)
        Optional<Task> taskOpt = findById(taskId);
        if (taskOpt.isEmpty()) {
            return TaskCompleteResult.NOT_FOUND;
        }

        Task task = taskOpt.get();

        // Check if already completed (idempotent)
        if (task.status() == TaskStatus.DONE) {
            return TaskCompleteResult.ALREADY_DONE;
        }

        // Also treat FAILED as already terminal
        if (task.status() == TaskStatus.FAILED) {
            return TaskCompleteResult.ALREADY_DONE;
        }

        // Verify the spot owns this task
        if (task.assignedTo() != null && !spotId.equals(task.assignedTo())) {
            log.warn("Spot {} tried to complete task {} but it's assigned to {}", spotId, taskId, task.assignedTo());
            return TaskCompleteResult.WRONG_SPOT;
        }

        // Task must be RUNNING
        if (task.status() != TaskStatus.RUNNING) {
            log.warn("Task {} is not RUNNING (status: {}), treating as idempotent success", taskId, task.status());
            return TaskCompleteResult.ALREADY_DONE;
        }

        // Now perform the ATOMIC completion with job counter update in single
        // transaction
        String updateTaskSql = """
                    UPDATE tasks
                    SET status = 'DONE', finished_at = ?, runtime_ms = ?, iter = ?, fopt = ?, result = ?
                    WHERE id = ? AND assigned_to = ? AND status = 'RUNNING'
                """;

        try (Connection conn = db.getConnection()) {
            Timestamp now = Timestamp.from(Instant.now());

            // 1. Update task atomically (WHERE ensures only RUNNING tasks are updated)
            try (PreparedStatement ps = conn.prepareStatement(updateTaskSql)) {
                ps.setTimestamp(1, now);
                ps.setLong(2, runtimeMs);
                setIntOrNull(ps, 3, iter);
                setDoubleOrNull(ps, 4, fopt);
                ps.setString(5, result);
                ps.setString(6, taskId);
                ps.setString(7, spotId);

                int tasksUpdated = ps.executeUpdate();

                if (tasksUpdated == 0) {
                    // Task was already completed by another thread - this is idempotent success
                    conn.rollback();
                    return TaskCompleteResult.ALREADY_DONE;
                }
            }

            // 2. Task was updated (real transition) - now update job counters
            String jobId = task.jobId();
            if (jobId != null) {
                updateJobOnTaskComplete(conn, jobId, now);
            }

            conn.commit();
            log.info("Task {} completed by spot {} and job {} counters committed in same transaction",
                    taskId, spotId, jobId);
            return TaskCompleteResult.COMPLETED;

        } catch (SQLException e) {
            throw new RuntimeException("Failed to complete task: " + taskId, e);
        }
    }

    /**
     * Update job counters and status when a task completes.
     * Called within same transaction as task update.
     * THROWS exception if job not found (should not happen in normal operation).
     */
    private void updateJobOnTaskComplete(Connection conn, String jobId, Timestamp now) throws SQLException {
        // Increment completed_tasks and update status in one statement
        String sql = """
                    UPDATE jobs
                    SET completed_tasks = completed_tasks + 1,
                        started_at = COALESCE(started_at, ?),
                        status = CASE
                            WHEN completed_tasks + failed_tasks + 1 >= total_tasks THEN 'COMPLETED'
                            ELSE 'RUNNING'
                        END,
                        finished_at = CASE
                            WHEN completed_tasks + failed_tasks + 1 >= total_tasks THEN ?
                            ELSE finished_at
                        END
                    WHERE id = ?
                """;

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setTimestamp(1, now);
            ps.setTimestamp(2, now);
            ps.setString(3, jobId);
            int rowsUpdated = ps.executeUpdate();

            if (rowsUpdated != 1) {
                log.error("CRITICAL: updateJobOnTaskComplete updated {} rows for job {} (expected 1)",
                        rowsUpdated, jobId);
                throw new SQLException(
                        "Job update failed: job " + jobId + " not found, updated " + rowsUpdated + " rows");
            }

            log.info("Job {} incremented completed_tasks (same transaction)", jobId);
        }
    }

    @Override
    public TaskFailResult failIdempotent(String taskId, String spotId, String errorMessage, boolean retriable) {
        // First check the current state (outside transaction for idempotency checks)
        Optional<Task> taskOpt = findById(taskId);
        if (taskOpt.isEmpty()) {
            return TaskFailResult.NOT_FOUND;
        }

        Task task = taskOpt.get();

        // Check if already terminal (idempotent)
        if (task.status() == TaskStatus.DONE || task.status() == TaskStatus.FAILED
                || task.status() == TaskStatus.CANCELLED) {
            return TaskFailResult.ALREADY_TERMINAL;
        }

        // Verify the spot owns this task
        if (task.assignedTo() != null && !spotId.equals(task.assignedTo())) {
            log.warn("Spot {} tried to fail task {} but it's assigned to {}", spotId, taskId, task.assignedTo());
            return TaskFailResult.WRONG_SPOT;
        }

        // Task must be RUNNING
        if (task.status() != TaskStatus.RUNNING) {
            log.warn("Task {} is not RUNNING (status: {}), treating as already terminal", taskId, task.status());
            return TaskFailResult.ALREADY_TERMINAL;
        }

        boolean willRetry = retriable && task.canRetry();
        String jobId = task.jobId();

        if (willRetry) {
            // Retry - just reset task to NEW, don't update job counters
            resetToNew(taskId);
            log.debug("Task {} failed by spot {}, will retry (attempt {}/{})",
                    taskId, spotId, task.attempts() + 1, task.maxAttempts());
            return TaskFailResult.RETRIED;
        } else {
            // Permanent failure - update both task AND job counters atomically
            return markFailedWithJobUpdate(taskId, jobId, errorMessage);
        }
    }

    /**
     * Mark task as permanently failed and update job counters atomically.
     */
    private TaskFailResult markFailedWithJobUpdate(String taskId, String jobId, String errorMessage) {
        String updateTaskSql = """
                    UPDATE tasks
                    SET status = 'FAILED', error_message = ?, finished_at = ?
                    WHERE id = ? AND status = 'RUNNING'
                """;

        try (Connection conn = db.getConnection()) {
            Timestamp now = Timestamp.from(Instant.now());

            // 1. Update task atomically
            try (PreparedStatement ps = conn.prepareStatement(updateTaskSql)) {
                ps.setString(1, errorMessage);
                ps.setTimestamp(2, now);
                ps.setString(3, taskId);

                int tasksUpdated = ps.executeUpdate();

                if (tasksUpdated == 0) {
                    // Task was already completed/failed by another thread
                    conn.rollback();
                    return TaskFailResult.ALREADY_TERMINAL;
                }
            }

            // 2. Task was updated (real transition) - now update job counters
            if (jobId != null) {
                updateJobOnTaskFail(conn, jobId, now);
            }

            conn.commit();
            log.debug("Task {} permanently failed and job {} updated", taskId, jobId);
            return TaskFailResult.FAILED;

        } catch (SQLException e) {
            throw new RuntimeException("Failed to mark task as failed: " + taskId, e);
        }
    }

    /**
     * Update job counters and status when a task permanently fails.
     * Called within same transaction as task update.
     * THROWS exception if job not found (should not happen in normal operation).
     */
    private void updateJobOnTaskFail(Connection conn, String jobId, Timestamp now) throws SQLException {
        String sql = """
                    UPDATE jobs
                    SET failed_tasks = failed_tasks + 1,
                        started_at = COALESCE(started_at, ?),
                        status = CASE
                            WHEN completed_tasks + failed_tasks + 1 >= total_tasks THEN 'COMPLETED'
                            ELSE 'RUNNING'
                        END,
                        finished_at = CASE
                            WHEN completed_tasks + failed_tasks + 1 >= total_tasks THEN ?
                            ELSE finished_at
                        END
                    WHERE id = ?
                """;

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setTimestamp(1, now);
            ps.setTimestamp(2, now);
            ps.setString(3, jobId);
            int rowsUpdated = ps.executeUpdate();

            if (rowsUpdated != 1) {
                log.error("CRITICAL: updateJobOnTaskFail updated {} rows for job {} (expected 1)",
                        rowsUpdated, jobId);
                throw new SQLException(
                        "Job update failed: job " + jobId + " not found, updated " + rowsUpdated + " rows");
            }

            log.info("Job {} incremented failed_tasks (same transaction)", jobId);
        }
    }

    @Override
    public boolean updateStatus(String taskId, TaskStatus status) {
        String sql = "UPDATE tasks SET status = ? WHERE id = ?";

        try (Connection conn = db.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, status.name());
            ps.setString(2, taskId);

            int updated = ps.executeUpdate();
            conn.commit();
            return updated > 0;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to update task status: " + taskId, e);
        }
    }

    @Override
    public List<Task> findRunningBySpotId(String spotId) {
        String sql = "SELECT * FROM tasks WHERE assigned_to = ? AND status = 'RUNNING' ORDER BY started_at";
        try (Connection conn = db.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, spotId);
            return executeQuery(ps);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find running tasks for spot: " + spotId, e);
        }
    }

    // Helper methods

    private List<Task> executeQuery(PreparedStatement ps) throws SQLException {
        List<Task> results = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                results.add(mapRow(rs));
            }
        }
        return results;
    }

    private Task mapRow(ResultSet rs) throws SQLException {
        return Task.builder()
                .id(rs.getString("id"))
                .jobId(rs.getString("job_id"))
                .payload(rs.getString("payload"))
                .status(TaskStatus.valueOf(rs.getString("status")))
                .assignedTo(rs.getString("assigned_to"))
                .priority(rs.getInt("priority"))
                .attempts(rs.getInt("attempts"))
                .maxAttempts(rs.getInt("max_attempts"))
                .errorMessage(rs.getString("error_message"))
                .createdAt(toInstant(rs.getTimestamp("created_at")))
                .startedAt(toInstant(rs.getTimestamp("started_at")))
                .finishedAt(toInstant(rs.getTimestamp("finished_at")))
                .runtimeMs(getLongOrNull(rs, "runtime_ms"))
                .iter(getIntOrNull(rs, "iter"))
                .fopt(getDoubleOrNull(rs, "fopt"))
                .result(rs.getString("result"))
                .algorithm(rs.getString("algorithm"))
                .optimizerId(rs.getString("optimizer_id"))
                .function(rs.getString("function"))
                .inputIterations(getIntOrNull(rs, "input_iterations"))
                .inputAgents(getIntOrNull(rs, "input_agents"))
                .inputDimension(getIntOrNull(rs, "input_dimension"))
                .build();
    }

    private static Instant toInstant(Timestamp ts) {
        return ts != null ? ts.toInstant() : null;
    }

    private static void setTimestamp(PreparedStatement ps, int index, Instant instant) throws SQLException {
        if (instant != null) {
            ps.setTimestamp(index, Timestamp.from(instant));
        } else {
            ps.setNull(index, Types.TIMESTAMP);
        }
    }

    private static void setLongOrNull(PreparedStatement ps, int index, Long value) throws SQLException {
        if (value != null) {
            ps.setLong(index, value);
        } else {
            ps.setNull(index, Types.BIGINT);
        }
    }

    private static void setIntOrNull(PreparedStatement ps, int index, Integer value) throws SQLException {
        if (value != null) {
            ps.setInt(index, value);
        } else {
            ps.setNull(index, Types.INTEGER);
        }
    }

    private static void setDoubleOrNull(PreparedStatement ps, int index, Double value) throws SQLException {
        if (value != null) {
            ps.setDouble(index, value);
        } else {
            ps.setNull(index, Types.DOUBLE);
        }
    }

    private static Long getLongOrNull(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Integer getIntOrNull(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static Double getDoubleOrNull(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }
}
