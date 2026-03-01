package orhestra.coordinator.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * Structured task specification — what the coordinator sends to SPOTs.
 * No raw shell commands, no absolute paths.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TaskSpec(
        @JsonProperty("kind") String kind,
        @JsonProperty("optimizerId") String optimizerId,
        @JsonProperty("optimizerVersion") String optimizerVersion,
        @JsonProperty("algorithm") String algorithm,
        @JsonProperty("function") String function,
        @JsonProperty("params") Map<String, Object> params) {

    public static final String KIND_OPTIMIZATION_RUN = "OPTIMIZATION_RUN";

    public void validate() {
        if (kind == null || kind.isBlank())
            throw new IllegalArgumentException("kind is required");
        if (optimizerId == null || optimizerId.isBlank())
            throw new IllegalArgumentException("optimizerId is required");
        if (algorithm == null || algorithm.isBlank())
            throw new IllegalArgumentException("algorithm is required");
        if (function == null || function.isBlank())
            throw new IllegalArgumentException("function is required");
    }

    /** Convenience: get int param with default */
    public int intParam(String key, int defaultValue) {
        if (params == null)
            return defaultValue;
        Object v = params.get(key);
        if (v instanceof Number n)
            return n.intValue();
        return defaultValue;
    }

    /** Convenience: get long param with default */
    public long longParam(String key, long defaultValue) {
        if (params == null)
            return defaultValue;
        Object v = params.get(key);
        if (v instanceof Number n)
            return n.longValue();
        return defaultValue;
    }
}
