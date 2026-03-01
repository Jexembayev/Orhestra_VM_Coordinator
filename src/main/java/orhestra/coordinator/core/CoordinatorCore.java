package orhestra.coordinator.core;

import orhestra.coordinator.config.Dependencies;
import orhestra.coordinator.model.Task;
import orhestra.coordinator.server.CoordinatorNettyServer;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Core coordinator operations.
 * 
 * This class now delegates to the Dependencies container services.
 * Previously used legacy DAOs directly.
 */
public class CoordinatorCore {

    /** Get the dependencies from the running server */
    private Dependencies deps() {
        return CoordinatorNettyServer.dependencies();
    }

    // ---- SPOTS ----
    public void heartbeat(String spotId, double cpu, int running, int cores, String ip) {
        deps().spotService().heartbeat(spotId, ip, cpu, running, cores, 0, 0);
    }

    /** Mark stale spots as DOWN and free their tasks */
    public int sweepOffline(Duration offline, Consumer<String> log) {
        int reaped = deps().spotService().reapStaleSpots();
        if (reaped > 0 && log != null) {
            log.accept("Marked DOWN: " + reaped);
        }
        return reaped;
    }

    // ---- TASKS ----
    public Optional<TaskPick> getTaskFor(String spotId) {
        List<Task> claimed = deps().taskService().claimTasks(spotId, 1);
        if (claimed.isEmpty()) {
            return Optional.empty();
        }
        Task t = claimed.get(0);
        return Optional.of(new TaskPick(t.id(), t.payload()));
    }

    public void taskDone(String taskId) {
        deps().taskRepository().complete(taskId, null, 0, null, null, null);
    }

    public void taskFailed(String taskId) {
        deps().taskRepository().fail(taskId, null, "Failed", false);
    }

    public int freeTasksOf(String spotId) {
        return deps().taskRepository().freeTasksForSpot(spotId);
    }

    /** Simple holder for claimed task info (replaces legacy TaskDao.TaskPick) */
    public record TaskPick(String id, String payload) {
    }
}
