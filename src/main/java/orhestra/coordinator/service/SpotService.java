package orhestra.coordinator.service;

import orhestra.coordinator.config.CoordinatorConfig;
import orhestra.coordinator.model.Spot;
import orhestra.coordinator.model.SpotStatus;
import orhestra.coordinator.repository.SpotRepository;
import orhestra.coordinator.repository.TaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Service layer for SPOT node operations.
 * Handles heartbeats, registration, and staleness detection.
 */
public class SpotService {

    private static final Logger log = LoggerFactory.getLogger(SpotService.class);

    private final SpotRepository spotRepository;
    private final TaskRepository taskRepository;
    private final CoordinatorConfig config;

    public SpotService(SpotRepository spotRepository, TaskRepository taskRepository, CoordinatorConfig config) {
        this.spotRepository = spotRepository;
        this.taskRepository = taskRepository;
        this.config = config;
    }

    /**
     * Register a new SPOT and return its ID.
     */
    public String registerSpot(String ipAddress) {
        String spotId = spotRepository.generateId();

        Spot spot = Spot.builder()
                .id(spotId)
                .ipAddress(ipAddress)
                .status(SpotStatus.UP)
                .lastHeartbeat(Instant.now())
                .registeredAt(Instant.now())
                .build();

        spotRepository.save(spot);
        log.info("Registered new SPOT: {} from {}", spotId, ipAddress);

        return spotId;
    }

    /**
     * Process heartbeat from a SPOT.
     */
    public void heartbeat(String spotId, String ipAddress, double cpuLoad, int runningTasks, int totalCores,
            long ramUsedMb, long ramTotalMb) {
        spotRepository.heartbeat(spotId, ipAddress, cpuLoad, runningTasks, totalCores, ramUsedMb, ramTotalMb);
        log.debug("Heartbeat from spot {} (cpu={}%, tasks={}, cores={}, ram={}/{}MB)", spotId, cpuLoad, runningTasks,
                totalCores, ramUsedMb, ramTotalMb);
    }

    /**
     * Find a SPOT by ID.
     */
    public Optional<Spot> findById(String spotId) {
        return spotRepository.findById(spotId);
    }

    /**
     * Get all SPOTs.
     */
    public List<Spot> findAll() {
        return spotRepository.findAll();
    }

    /**
     * Get SPOTs by status.
     */
    public List<Spot> findByStatus(SpotStatus status) {
        return spotRepository.findByStatus(status);
    }

    /**
     * Get active (UP) SPOTs.
     */
    public List<Spot> findActive() {
        return spotRepository.findByStatus(SpotStatus.UP);
    }

    /**
     * Count active SPOTs.
     */
    public int countActive() {
        return spotRepository.countByStatus(SpotStatus.UP);
    }

    /**
     * Check for stale SPOTs and mark them as DOWN.
     * Also frees any tasks assigned to those SPOTs.
     * 
     * @return number of SPOTs marked as DOWN
     */
    public int reapStaleSpots() {
        Instant cutoff = Instant.now().minus(config.spotHeartbeatTimeout());
        List<String> staleIds = spotRepository.markStaleAsDown(cutoff);

        // Free tasks assigned to stale SPOTs
        int totalFreed = 0;
        for (String spotId : staleIds) {
            int freed = taskRepository.freeTasksForSpot(spotId);
            totalFreed += freed;
        }

        if (!staleIds.isEmpty()) {
            log.info("Reaped {} stale SPOTs, freed {} tasks", staleIds.size(), totalFreed);
        }

        return staleIds.size();
    }

    /**
     * Delete a SPOT.
     */
    public boolean delete(String spotId) {
        // First free any assigned tasks
        taskRepository.freeTasksForSpot(spotId);
        return spotRepository.delete(spotId);
    }
}
