package orhestra.coordinator.simulation;

import orhestra.coordinator.core.AppBus;
import orhestra.coordinator.service.SpotService;
import orhestra.coordinator.service.TaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Service managing simulated SPOT workers.
 * Call start() to spawn N workers, stop() to shut them all down.
 */
public final class SimulationService {

    private static final Logger log = LoggerFactory.getLogger(SimulationService.class);

    private final SpotService spotService;
    private final TaskService taskService;

    private ExecutorService executor;
    private final List<String> spotIds = new ArrayList<>();
    private volatile boolean running;

    public SimulationService(SpotService spotService, TaskService taskService) {
        this.spotService = spotService;
        this.taskService = taskService;
    }

    /**
     * Start N simulated workers.
     */
    public synchronized void start(int workers, int delayMinMs, int delayMaxMs, double failRate) {
        if (running) {
            log.warn("Simulation already running");
            return;
        }

        spotIds.clear();
        executor = Executors.newFixedThreadPool(workers, r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            return t;
        });

        for (int i = 1; i <= workers; i++) {
            String spotId = "sim-" + i;
            spotIds.add(spotId);

            // Pre-register the spot so it appears in the table immediately
            try {
                spotService.heartbeat(spotId, "127.0.0.1", 0.0, 0, 4, 0, 0);
            } catch (Exception e) {
                log.debug("Pre-register sim spot {} failed, will retry in worker", spotId);
            }

            executor.submit(new SimulatedSpotWorker(
                    spotId, spotService, taskService, delayMinMs, delayMaxMs, failRate));
        }

        running = true;
        AppBus.fireSpotsChanged();
        log.info("Simulation started: {} workers, delay {}..{}ms, failRate {}",
                workers, delayMinMs, delayMaxMs, failRate);
    }

    /**
     * Stop all simulated workers and remove sim-spots from registry.
     */
    public synchronized void stop() {
        if (!running)
            return;

        running = false;

        // Shut down executor
        if (executor != null) {
            executor.shutdownNow();
            try {
                executor.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            executor = null;
        }

        // Remove sim-spots from registry
        for (String id : spotIds) {
            try {
                spotService.delete(id);
            } catch (Exception e) {
                log.debug("Failed to delete sim spot {}: {}", id, e.getMessage());
            }
        }
        spotIds.clear();

        AppBus.fireSpotsChanged();
        AppBus.fireTasksChanged();
        log.info("Simulation stopped");
    }

    public boolean isRunning() {
        return running;
    }
}
