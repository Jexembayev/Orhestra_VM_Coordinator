package orhestra.coordinator.repository;

import orhestra.coordinator.model.Spot;
import orhestra.coordinator.model.SpotStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Repository interface for SPOT node persistence.
 */
public interface SpotRepository {

    /**
     * Register a new SPOT or update existing one.
     * 
     * @param spot the spot to save
     */
    void save(Spot spot);

    /**
     * Find a SPOT by ID.
     * 
     * @param spotId the SPOT ID
     * @return the spot if found
     */
    Optional<Spot> findById(String spotId);

    /**
     * Get all SPOTs.
     * 
     * @return list of all spots
     */
    List<Spot> findAll();

    /**
     * Get all SPOTs with the given status.
     * 
     * @param status the status filter
     * @return list of spots
     */
    List<Spot> findByStatus(SpotStatus status);

    /**
     * Update heartbeat for a SPOT.
     * Creates the SPOT if it doesn't exist.
     * 
     * @param spotId       the SPOT ID
     * @param ipAddress    the IP address
     * @param cpuLoad      current CPU load percentage
     * @param runningTasks number of tasks currently running
     * @param totalCores   total CPU cores
     */
    void heartbeat(String spotId, String ipAddress, double cpuLoad, int runningTasks, int totalCores, long ramUsedMb,
            long ramTotalMb);

    /**
     * Mark SPOTs as DOWN if they haven't sent heartbeat recently.
     * 
     * @param lastHeartbeatBefore SPOTs with heartbeat before this are marked DOWN
     * @return list of SPOT IDs that were marked DOWN
     */
    List<String> markStaleAsDown(Instant lastHeartbeatBefore);

    /**
     * Delete a SPOT.
     * 
     * @param spotId the SPOT ID
     * @return true if deleted
     */
    boolean delete(String spotId);

    /**
     * Get total count of SPOTs.
     * 
     * @return count
     */
    int count();

    /**
     * Get count of SPOTs by status.
     * 
     * @param status the status
     * @return count
     */
    int countByStatus(SpotStatus status);

    /**
     * Generate a new unique SPOT ID.
     * 
     * @return unique ID
     */
    String generateId();
}
