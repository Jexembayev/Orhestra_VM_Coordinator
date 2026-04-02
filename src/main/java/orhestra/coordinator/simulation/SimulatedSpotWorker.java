package orhestra.coordinator.simulation;

import orhestra.coordinator.core.AppBus;
import orhestra.coordinator.model.Task;
import orhestra.coordinator.model.TaskCompleteResult;
import orhestra.coordinator.model.TaskFailResult;
import orhestra.coordinator.service.SpotService;
import orhestra.coordinator.service.TaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A single simulated SPOT worker.
 * Registers, then loops: claim → sleep → complete/fail → heartbeat.
 * Stops cleanly on Thread.interrupt().
 */
public final class SimulatedSpotWorker implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(SimulatedSpotWorker.class);

    private final String spotId;
    private final SpotService spotService;
    private final TaskService taskService;
    private final int delayMinMs;
    private final int delayMaxMs;
    private final double failRate;

    public SimulatedSpotWorker(String spotId,
            SpotService spotService,
            TaskService taskService,
            int delayMinMs,
            int delayMaxMs,
            double failRate) {
        this.spotId = spotId;
        this.spotService = spotService;
        this.taskService = taskService;
        this.delayMinMs = delayMinMs;
        this.delayMaxMs = delayMaxMs;
        this.failRate = failRate;
    }

    @Override
    public void run() {
        Thread.currentThread().setName("sim-worker-" + spotId);

        // Register this simulated spot
        try {
            spotService.heartbeat(spotId, "127.0.0.1", 0.0, 0, 4, 0, 0);
        } catch (Exception e) {
            // Spot may not exist yet — register first
            try {
                // Use the spotId directly by registering, then heartbeat
                spotService.heartbeat(spotId, "127.0.0.1", 0.0, 0, 4, 0, 0);
            } catch (Exception ex) {
                log.debug("Sim worker {} could not register: {}", spotId, ex.getMessage());
            }
        }

        log.info("Sim worker {} started", spotId);

        while (!Thread.currentThread().isInterrupted()) {
            try {
                // 1. Claim one task
                List<Task> claimed = taskService.claimTasks(spotId, 1);

                if (claimed.isEmpty()) {
                    // No tasks — idle sleep
                    Thread.sleep(500);
                    sendHeartbeat(0);
                    continue;
                }

                Task task = claimed.get(0);
                sendHeartbeat(1);
                AppBus.fireSpotsChanged();

                // 2. Simulate work
                int delay = delayMinMs >= delayMaxMs ? delayMinMs
                        : ThreadLocalRandom.current().nextInt(delayMinMs, delayMaxMs);
                Thread.sleep(delay);

                // 3. Complete or fail
                boolean shouldFail = failRate > 0 && ThreadLocalRandom.current().nextDouble() < failRate;

                if (shouldFail) {
                    TaskFailResult res = taskService.failTaskIdempotent(
                            task.id(), spotId, "Simulated failure", false, null, null);
                    log.debug("Sim {} failed task {} → {}", spotId, task.id(), res);
                } else {
                    double fopt = ThreadLocalRandom.current().nextDouble() * 100.0;
                    int iter = ThreadLocalRandom.current().nextInt(50, 500);
                    String result = String.format("{\"sim\":true,\"fopt\":%.6f,\"iter\":%d}", fopt, iter);

                    TaskCompleteResult res = taskService.completeTaskIdempotent(
                            task.id(), spotId, delay, iter, fopt, result, null);
                    log.debug("Sim {} completed task {} → {}", spotId, task.id(), res);
                }

                sendHeartbeat(0);
                AppBus.fireTasksChanged();
                AppBus.fireSpotsChanged();

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.warn("Sim worker {} error: {}", spotId, e.getMessage());
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        log.info("Sim worker {} stopped", spotId);
    }

    private void sendHeartbeat(int runningTasks) {
        try {
            double cpu = ThreadLocalRandom.current().nextDouble(10, 90);
            spotService.heartbeat(spotId, "127.0.0.1", cpu, runningTasks, 4, 0, 0);
        } catch (Exception e) {
            log.debug("Sim {} heartbeat failed: {}", spotId, e.getMessage());
        }
    }
}
