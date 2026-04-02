package orhestra.coordinator.api.internal.v1.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Request DTO for SPOT heartbeat.
 * POST /internal/v1/heartbeat
 * 
 * Ignores unknown fields to allow agent version evolution.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record HeartbeatRequest(
        @JsonProperty("spotId")         String spotId,
        @JsonProperty("cpuLoad")        double cpuLoad,
        @JsonProperty("runningTasks")   int runningTasks,
        @JsonProperty("totalCores")     int totalCores,
        @JsonProperty("ramUsedMb")      long ramUsedMb,
        @JsonProperty("ramTotalMb")     long ramTotalMb,
        // new fields (agent v2.2+)
        @JsonProperty("loadAvg1m")      double loadAvg1m,
        @JsonProperty("swapUsedMb")     long swapUsedMb,
        @JsonProperty("diskFreeGb")     double diskFreeGb,
        @JsonProperty("jvmHeapUsedMb")  long jvmHeapUsedMb,
        @JsonProperty("jvmHeapMaxMb")   long jvmHeapMaxMb,
        @JsonProperty("cachedArtifacts") int cachedArtifacts) {

    public void validate() {
        if (spotId == null || spotId.isBlank()) {
            throw new IllegalArgumentException("spotId is required");
        }
        if (cpuLoad < 0 || cpuLoad > 100) {
            throw new IllegalArgumentException("cpuLoad must be between 0 and 100");
        }
        if (runningTasks < 0) {
            throw new IllegalArgumentException("runningTasks must be non-negative");
        }
        if (totalCores <= 0) {
            throw new IllegalArgumentException("totalCores must be positive");
        }
    }
}
