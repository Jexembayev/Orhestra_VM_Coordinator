package orhestra.coordinator.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import orhestra.coordinator.config.CoordinatorConfig;
import orhestra.coordinator.config.Dependencies;
import orhestra.coordinator.model.*;
import orhestra.coordinator.service.SpotTaskBlacklist;
import org.junit.jupiter.api.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for capability-aware scheduling:
 * A) register spot with capabilities → claim matching tasks
 * B) no compatible spot → tasks stay queued
 * C) UNSUPPORTED → never reassign to same spot
 */
class CapabilitySchedulingTest {

    private static Dependencies deps;
    private static final String OPT_ID = "optimizer-java-runner";

    @BeforeAll
    static void setUp() {
        CoordinatorConfig config = CoordinatorConfig.defaults()
                .withDatabaseUrl(
                        "jdbc:h2:mem:captest-" + System.nanoTime() + ";MODE=PostgreSQL;DATABASE_TO_UPPER=FALSE");
        deps = Dependencies.create(config);
    }

    @AfterAll
    static void tearDown() {
        if (deps != null)
            deps.close();
    }

    @Test
    void capabilityMatchingClaimSucceeds() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        // Register a spot with PSO+GA capabilities
        String capJson = mapper.writeValueAsString(Map.of(
                "optimizers", List.of(Map.of(
                        "optimizerId", OPT_ID,
                        "version", "2.0.0",
                        "algorithms", List.of("PSO", "GA"),
                        "functions", List.of("sphere", "rosenbrock")))));
        String spotId = deps.spotService().registerSpot("127.0.0.1", 4, 8192, 7, capJson, "local");

        // Create a task with matching algorithm (PSO)
        Task psoTask = Task.builder()
                .id("task-pso-1")
                .jobId("job-1")
                .payload("{}")
                .algorithm("PSO")
                .optimizerId(OPT_ID)
                .function("sphere")
                .status(TaskStatus.NEW)
                .createdAt(Instant.now())
                .build();
        deps.taskService().createTasks(List.of(psoTask));

        // Claim — should succeed
        List<Task> claimed = deps.taskService().claimTasks(spotId, 5);
        assertEquals(1, claimed.size(), "Should claim PSO task");
        assertEquals("task-pso-1", claimed.get(0).id());
    }

    @Test
    void capabilityMismatchNoTasks() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        // Register spot supporting only DE algorithm
        String capJson = mapper.writeValueAsString(Map.of(
                "optimizers", List.of(Map.of(
                        "optimizerId", OPT_ID,
                        "version", "2.0.0",
                        "algorithms", List.of("DE"),
                        "functions", List.of("sphere")))));
        String spotId = deps.spotService().registerSpot("127.0.0.2", 4, 8192, 7, capJson, "local");

        // Create a task requiring ABC algorithm (not supported)
        Task abcTask = Task.builder()
                .id("task-abc-1")
                .jobId("job-2")
                .payload("{}")
                .algorithm("ABC")
                .optimizerId(OPT_ID)
                .function("sphere")
                .status(TaskStatus.NEW)
                .createdAt(Instant.now())
                .build();
        deps.taskService().createTasks(List.of(abcTask));

        // Claim — should return empty (spot doesn't support ABC)
        List<Task> claimed = deps.taskService().claimTasks(spotId, 5);
        assertTrue(claimed.isEmpty(), "Should not claim ABC task when spot only supports DE");

        // Task should still be NEW
        Task remaining = deps.taskService().findById("task-abc-1").orElseThrow();
        assertEquals(TaskStatus.NEW, remaining.status(), "Task should remain NEW");
    }

    @Test
    void blacklistPreventsReassignment() {
        SpotTaskBlacklist blacklist = new SpotTaskBlacklist();
        blacklist.blacklist("spot-x", "task-y");

        assertTrue(blacklist.isBlacklisted("spot-x", "task-y"));
        assertFalse(blacklist.isBlacklisted("spot-x", "task-z"));
        assertFalse(blacklist.isBlacklisted("spot-w", "task-y"));

        blacklist.clearForTask("task-y");
        assertFalse(blacklist.isBlacklisted("spot-x", "task-y"));
    }
}
