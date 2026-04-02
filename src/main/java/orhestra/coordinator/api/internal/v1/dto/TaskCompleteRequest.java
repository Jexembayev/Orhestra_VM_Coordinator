package orhestra.coordinator.api.internal.v1.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Request DTO for completing a task.
 * POST /internal/v1/tasks/{taskId}/complete
 * 
 * Ignores unknown fields to allow agent version evolution.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TaskCompleteRequest(
        @JsonProperty("spotId")     String spotId,
        @JsonProperty("runtimeMs")  long runtimeMs,
        @JsonProperty("iter")       Integer iter,
        @JsonProperty("fopt")       Double fopt,
        @JsonProperty("bestPos")    JsonNode bestPos,
        @JsonProperty("charts")     JsonNode charts,
        @JsonProperty("peakRamMb")  Long peakRamMb   // new in agent v2.2+
) {
    public void validate() {
        if (spotId == null || spotId.isBlank()) {
            throw new IllegalArgumentException("spotId is required");
        }
        if (runtimeMs < 0) {
            throw new IllegalArgumentException("runtimeMs must be non-negative");
        }
    }

    /** Get result as JSON string for storage */
    public String resultJson() {
        if (bestPos == null && charts == null) {
            return null;
        }
        // Combine bestPos and charts into result object
        // For now, prefer charts if present, otherwise use bestPos
        if (charts != null) {
            return charts.toString();
        }
        return bestPos != null ? bestPos.toString() : null;
    }
}
