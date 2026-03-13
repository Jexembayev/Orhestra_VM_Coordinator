package orhestra.coordinator.ui;

import java.time.Instant;

/**
 * UI view model for displaying task information in the monitoring table.
 */
public record TaskInfo(
                String id,
                String algId,
                String status,
                Integer iter,
                Long runtimeMs,
                Double fopt,
                String payload,
                String assignedTo,
                Instant startedAt,
                Instant finishedAt,
                Integer inputIterations,
                Integer inputAgents,
                Integer inputDimension,
                String result) {  // raw JSON from DB: bestPos array e.g. "[0.12, -0.34, ...]"
}
