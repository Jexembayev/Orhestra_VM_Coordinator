package orhestra.coordinator.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Capabilities a SPOT declares during registration.
 * Used by the coordinator for capability-aware scheduling.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SpotCapabilities(
        @JsonProperty("optimizers") List<OptimizerCapability> optimizers) {

    /**
     * A single optimizer that a SPOT can execute.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OptimizerCapability(
            @JsonProperty("optimizerId") String optimizerId,
            @JsonProperty("version") String version,
            @JsonProperty("algorithms") List<String> algorithms,
            @JsonProperty("functions") List<String> functions) {

        /** Check if this capability supports a given task spec */
        public boolean supports(TaskSpec spec) {
            if (!optimizerId.equals(spec.optimizerId()))
                return false;
            if (algorithms != null && !algorithms.isEmpty()
                    && !algorithms.contains(spec.algorithm()))
                return false;
            if (functions != null && !functions.isEmpty()
                    && !functions.contains(spec.function()))
                return false;
            return true;
        }
    }

    /** Empty capabilities (supports nothing) */
    public static SpotCapabilities empty() {
        return new SpotCapabilities(List.of());
    }

    /** Check if any optimizer supports the given task spec */
    public boolean canExecute(TaskSpec spec) {
        if (optimizers == null || optimizers.isEmpty())
            return false;
        return optimizers.stream().anyMatch(o -> o.supports(spec));
    }

    /** Get list of supported optimizer IDs */
    public List<String> optimizerIds() {
        if (optimizers == null)
            return List.of();
        return optimizers.stream().map(OptimizerCapability::optimizerId).toList();
    }

    /** Get list of supported algorithms (across all optimizers) */
    public List<String> allAlgorithms() {
        if (optimizers == null)
            return List.of();
        return optimizers.stream()
                .flatMap(o -> o.algorithms() != null ? o.algorithms().stream() : java.util.stream.Stream.empty())
                .distinct()
                .toList();
    }
}
