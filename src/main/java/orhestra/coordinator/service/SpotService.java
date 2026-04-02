package orhestra.coordinator.service;

import orhestra.coordinator.config.CoordinatorConfig;
import orhestra.coordinator.core.AppBus;
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
     * Register a new SPOT (legacy — no capabilities).
     */
    public String registerSpot(String ipAddress) {
        return registerSpot(ipAddress, 0, 0, 0, null, null);
    }

    /**
     * Register a new SPOT with capabilities.
     */
    public String registerSpot(String ipAddress, int cores, long ramMb,
            int maxConcurrent, String capabilitiesJson, String labels) {
        return registerSpot(ipAddress, cores, ramMb, maxConcurrent, capabilitiesJson, labels,
                null, null, null, null, 0);
    }

    /**
     * Register a new SPOT with full system info (agent v2.2+).
     */
    public String registerSpot(String ipAddress, int cores, long ramMb,
            int maxConcurrent, String capabilitiesJson, String labels,
            String hostname, String agentVersion, String osName, String jvmVersion, double totalDiskGb) {
        String spotId = spotRepository.generateId();

        Spot spot = Spot.builder()
                .id(spotId)
                .ipAddress(ipAddress)
                .totalCores(cores)
                .ramTotalMb(ramMb)
                .maxConcurrent(maxConcurrent)
                .capabilitiesJson(capabilitiesJson)
                .labels(labels)
                .hostname(hostname)
                .agentVersion(agentVersion)
                .osName(osName)
                .diskFreeGb(totalDiskGb)
                .status(SpotStatus.UP)
                .lastHeartbeat(Instant.now())
                .registeredAt(Instant.now())
                .build();

        spotRepository.save(spot);
        log.info("Registered new SPOT: {} from {} hostname={} agent={} (cores={}, maxConcurrent={})",
                spotId, ipAddress, hostname, agentVersion, cores, maxConcurrent);

        return spotId;
    }

    /**
     * Process heartbeat from a SPOT.
     */
    public void heartbeat(String spotId, String ipAddress, double cpuLoad, int runningTasks, int totalCores,
            long ramUsedMb, long ramTotalMb) {
        heartbeat(spotId, ipAddress, cpuLoad, runningTasks, totalCores, ramUsedMb, ramTotalMb,
                0, 0, 0, 0, 0, 0);
    }

    /**
     * Process heartbeat from a SPOT (agent v2.2+ with extended metrics).
     */
    public void heartbeat(String spotId, String ipAddress, double cpuLoad, int runningTasks, int totalCores,
            long ramUsedMb, long ramTotalMb,
            double loadAvg1m, long swapUsedMb, double diskFreeGb,
            long jvmHeapUsedMb, long jvmHeapMaxMb, int cachedArtifacts) {
        spotRepository.heartbeat(spotId, ipAddress, cpuLoad, runningTasks, totalCores, ramUsedMb, ramTotalMb,
                loadAvg1m, swapUsedMb, diskFreeGb, jvmHeapUsedMb, jvmHeapMaxMb, cachedArtifacts);
        log.debug("Heartbeat from spot {} (cpu={}%, tasks={}, diskFreeGb={}, jvmHeap={}/{}MB)",
                spotId, cpuLoad, runningTasks, diskFreeGb, jvmHeapUsedMb, jvmHeapMaxMb);
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
     * Check for stale SPOTs, free their tasks, and delete them.
     * 
     * @return number of SPOTs removed
     */
    public int reapStaleSpots() {
        Instant cutoff = Instant.now().minus(config.spotHeartbeatTimeout());
        List<String> staleIds = spotRepository.markStaleAsDown(cutoff);

        // Free tasks assigned to stale SPOTs, then delete them
        int totalFreed = 0;
        for (String spotId : staleIds) {
            int freed = taskRepository.freeTasksForSpot(spotId);
            totalFreed += freed;
            spotRepository.delete(spotId);
        }

        if (!staleIds.isEmpty()) {
            log.info("Reaped {} stale SPOTs, freed {} tasks", staleIds.size(), totalFreed);
            AppBus.fireSpotsChanged();
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
