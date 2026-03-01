package orhestra.coordinator.ui;

import java.time.Instant;

/**
 * UI view model for displaying spot information in monitoring cards.
 */
public record SpotInfo(
        String spotId,
        Double cpuLoad,
        Integer runningTasks,
        String status,
        Instant lastSeen,
        Integer totalCores,
        String lastIp,
        Long ramUsedMb,
        Long ramTotalMb,
        Integer maxConcurrent) {
}
