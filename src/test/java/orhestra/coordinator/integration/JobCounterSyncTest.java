package orhestra.coordinator.integration;

import orhestra.coordinator.model.ArtifactRef;
import orhestra.coordinator.model.*;
import orhestra.coordinator.store.Database;
import orhestra.coordinator.store.JdbcJobRepository;
import orhestra.coordinator.store.JdbcTaskRepository;
import org.junit.jupiter.api.*;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for job counter synchronization.
 * Verifies that job aggregates are correctly updated when tasks complete/fail.
 */
class JobCounterSyncTest {

    private Database db;
    private JdbcJobRepository jobRepository;
    private JdbcTaskRepository taskRepository;

    @BeforeEach
    void setUp() {
        db = new Database("jdbc:h2:mem:test-job-counter-" + System.nanoTime()
                + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_UPPER=FALSE", 2);
        jobRepository = new JdbcJobRepository(db);
        taskRepository = new JdbcTaskRepository(db);
    }

    @AfterEach
    void tearDown() {
        if (db != null) {
            db.close();
        }
    }

    @Test
    void completeTask_updatesJobCountersAndStatus() {
        // 1. Create job with 1 task
        Job job = Job.builder()
                .id("job-1")
                .artifact(new ArtifactRef("test-bucket", "test.jar", "http://localhost:9000"))
                .mainClass("Test")
                .config("{}")
                .status(JobStatus.PENDING)
                .totalTasks(1)
                .completedTasks(0)
                .failedTasks(0)
                .build();
        jobRepository.save(job);

        // 2. Create task for job
        Task task = Task.builder()
                .id("task-1")
                .jobId("job-1")
                .payload("{}")
                .status(TaskStatus.NEW)
                .build();
        taskRepository.save(task);

        // 3. Claim task (simulate SPOT claiming)
        List<Task> claimed = taskRepository.claimTasks("spot-1", 1);
        assertEquals(1, claimed.size());

        // 4. Complete task
        TaskCompleteResult result = taskRepository.completeIdempotent(
                "task-1", "spot-1", 1000L, 5, 0.01, "{\"result\":\"done\"}", null);
        assertEquals(TaskCompleteResult.COMPLETED, result);

        // 5. Verify job counters updated
        Job updatedJob = jobRepository.findById("job-1").orElseThrow();
        assertEquals(1, updatedJob.completedTasks(), "Job completedTasks should be 1");
        assertEquals(0, updatedJob.failedTasks(), "Job failedTasks should be 0");
        assertEquals(JobStatus.COMPLETED, updatedJob.status(), "Job should be COMPLETED");
        assertNotNull(updatedJob.startedAt(), "Job startedAt should be set");
        assertNotNull(updatedJob.finishedAt(), "Job finishedAt should be set");
    }

    @Test
    void completeTaskTwice_isIdempotent_jobCountersStay1() {
        // 1. Create job with 1 task
        Job job = Job.builder()
                .id("job-idempotent")
                .artifact(new ArtifactRef("test-bucket", "test.jar", "http://localhost:9000"))
                .mainClass("Test")
                .config("{}")
                .status(JobStatus.PENDING)
                .totalTasks(1)
                .build();
        jobRepository.save(job);

        // 2. Create and claim task
        Task task = Task.builder()
                .id("task-idempotent")
                .jobId("job-idempotent")
                .payload("{}")
                .status(TaskStatus.NEW)
                .build();
        taskRepository.save(task);
        taskRepository.claimTasks("spot-1", 1);

        // 3. Complete task first time
        TaskCompleteResult result1 = taskRepository.completeIdempotent(
                "task-idempotent", "spot-1", 1000L, 5, 0.01, null, null);
        assertEquals(TaskCompleteResult.COMPLETED, result1);

        // 4. Complete again (idempotent) - should NOT increment
        TaskCompleteResult result2 = taskRepository.completeIdempotent(
                "task-idempotent", "spot-1", 2000L, 10, 0.02, null, null);
        assertEquals(TaskCompleteResult.ALREADY_DONE, result2);

        // 5. Verify job counters stayed at 1
        Job updatedJob = jobRepository.findById("job-idempotent").orElseThrow();
        assertEquals(1, updatedJob.completedTasks(), "Job completedTasks should still be 1 (not 2)");
        assertEquals(0, updatedJob.failedTasks());
        assertEquals(JobStatus.COMPLETED, updatedJob.status());
    }

    @Test
    void failTaskPermanently_incrementsFailedTasksAndCompletesJob() {
        // 1. Create job with 1 task
        Job job = Job.builder()
                .id("job-fail")
                .artifact(new ArtifactRef("test-bucket", "test.jar", "http://localhost:9000"))
                .mainClass("Test")
                .config("{}")
                .status(JobStatus.PENDING)
                .totalTasks(1)
                .build();
        jobRepository.save(job);

        // 2. Create task with 0 retries left
        Task task = Task.builder()
                .id("task-fail")
                .jobId("job-fail")
                .payload("{}")
                .status(TaskStatus.NEW)
                .maxAttempts(1)
                .attempts(1) // Already at max
                .build();
        taskRepository.save(task);
        taskRepository.claimTasks("spot-1", 1);

        // 3. Fail task permanently (retriable=false or out of retries)
        TaskFailResult result = taskRepository.failIdempotent(
                "task-fail", "spot-1", "Test error", false, null, null);
        assertEquals(TaskFailResult.FAILED, result);

        // 4. Verify job counters
        Job updatedJob = jobRepository.findById("job-fail").orElseThrow();
        assertEquals(0, updatedJob.completedTasks());
        assertEquals(1, updatedJob.failedTasks(), "Job failedTasks should be 1");
        assertEquals(JobStatus.COMPLETED, updatedJob.status(), "Job should be COMPLETED (all tasks terminal)");
    }

    @Test
    void retryTask_doesNotIncrementJobCounters() {
        // 1. Create job with 1 task
        Job job = Job.builder()
                .id("job-retry")
                .artifact(new ArtifactRef("test-bucket", "test.jar", "http://localhost:9000"))
                .mainClass("Test")
                .config("{}")
                .status(JobStatus.PENDING)
                .totalTasks(1)
                .build();
        jobRepository.save(job);

        // 2. Create task with retries available
        Task task = Task.builder()
                .id("task-retry")
                .jobId("job-retry")
                .payload("{}")
                .status(TaskStatus.NEW)
                .maxAttempts(3)
                .attempts(0)
                .build();
        taskRepository.save(task);
        taskRepository.claimTasks("spot-1", 1);

        // 3. Report retriable failure - should reset to NEW
        TaskFailResult result = taskRepository.failIdempotent(
                "task-retry", "spot-1", "Transient error", true, null, null);
        assertEquals(TaskFailResult.RETRIED, result);

        // 4. Verify job counters NOT incremented
        Job updatedJob = jobRepository.findById("job-retry").orElseThrow();
        assertEquals(0, updatedJob.completedTasks(), "completedTasks unchanged");
        assertEquals(0, updatedJob.failedTasks(), "failedTasks unchanged for retry");
        assertEquals(JobStatus.PENDING, updatedJob.status(), "Job still PENDING");

        // 5. Verify task is back to NEW
        Task retryTask = taskRepository.findById("task-retry").orElseThrow();
        assertEquals(TaskStatus.NEW, retryTask.status());
        assertEquals(1, retryTask.attempts(), "attempts incremented");
    }

    @Test
    void completeMultipleTasks_jobCompletedOnlyWhenAllDone() {
        // 1. Create job with 2 tasks
        Job job = Job.builder()
                .id("job-multi")
                .artifact(new ArtifactRef("test-bucket", "test.jar", "http://localhost:9000"))
                .mainClass("Test")
                .config("{}")
                .status(JobStatus.PENDING)
                .totalTasks(2)
                .build();
        jobRepository.save(job);

        // 2. Create 2 tasks
        taskRepository
                .save(Task.builder().id("task-m1").jobId("job-multi").payload("{}").status(TaskStatus.NEW).build());
        taskRepository
                .save(Task.builder().id("task-m2").jobId("job-multi").payload("{}").status(TaskStatus.NEW).build());

        // 3. Claim both tasks
        taskRepository.claimTasks("spot-1", 2);

        // 4. Complete first task
        taskRepository.completeIdempotent("task-m1", "spot-1", 100L, null, null, null, null);

        // 5. Verify job is RUNNING (not COMPLETED yet)
        Job afterFirst = jobRepository.findById("job-multi").orElseThrow();
        assertEquals(1, afterFirst.completedTasks());
        assertEquals(JobStatus.RUNNING, afterFirst.status(), "Job should be RUNNING after 1 of 2 tasks");

        // 6. Complete second task
        taskRepository.completeIdempotent("task-m2", "spot-1", 100L, null, null, null, null);

        // 7. Verify job is now COMPLETED
        Job afterSecond = jobRepository.findById("job-multi").orElseThrow();
        assertEquals(2, afterSecond.completedTasks());
        assertEquals(JobStatus.COMPLETED, afterSecond.status(), "Job should be COMPLETED after 2 of 2 tasks");
    }
}
