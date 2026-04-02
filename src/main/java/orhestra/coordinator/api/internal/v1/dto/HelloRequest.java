package orhestra.coordinator.api.internal.v1.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Request body for POST /internal/v1/hello (SPOT registration).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record HelloRequest(
        @JsonProperty("spotInfo") SpotInfo spotInfo,
        @JsonProperty("capabilities") Capabilities capabilities) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SpotInfo(
            @JsonProperty("host")           String host,
            @JsonProperty("cpuCores")       int cpuCores,
            @JsonProperty("ramMb")          long ramMb,
            @JsonProperty("maxConcurrent")  int maxConcurrent,
            @JsonProperty("labels")         List<String> labels,
            // new fields (agent v2.2+)
            @JsonProperty("hostname")       String hostname,
            @JsonProperty("agentVersion")   String agentVersion,
            @JsonProperty("osName")         String osName,
            @JsonProperty("osVersion")      String osVersion,
            @JsonProperty("jvmVersion")     String jvmVersion,
            @JsonProperty("totalDiskGb")    double totalDiskGb) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Capabilities(
            @JsonProperty("optimizers") List<OptimizerInfo> optimizers) {

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record OptimizerInfo(
                @JsonProperty("optimizerId") String optimizerId,
                @JsonProperty("version") String version,
                @JsonProperty("algorithms") List<String> algorithms,
                @JsonProperty("functions") List<String> functions) {
        }
    }

    /** Convert capabilities to JSON string for DB storage */
    public String capabilitiesJson(com.fasterxml.jackson.databind.ObjectMapper mapper) {
        if (capabilities == null)
            return null;
        try {
            return mapper.writeValueAsString(capabilities);
        } catch (Exception e) {
            return null;
        }
    }

    /** Get labels as comma-separated string */
    public String labelsString() {
        if (spotInfo == null || spotInfo.labels() == null)
            return null;
        return String.join(",", spotInfo.labels());
    }
}
