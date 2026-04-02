package orhestra.coordinator.model;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable domain model representing a SPOT compute node.
 */
public final class Spot {
    private final String id;
    private final String ipAddress;
    private final double cpuLoad;
    private final int runningTasks;
    private final int totalCores;
    private final long ramUsedMb;
    private final long ramTotalMb;
    private final int maxConcurrent;
    private final String capabilitiesJson;
    private final String labels;
    private final SpotStatus status;
    private final Instant lastHeartbeat;
    private final Instant registeredAt;
    // fields from agent v2.2+
    private final String hostname;
    private final String agentVersion;
    private final String osName;
    private final double loadAvg1m;
    private final long swapUsedMb;
    private final double diskFreeGb;
    private final long jvmHeapUsedMb;
    private final long jvmHeapMaxMb;
    private final int cachedArtifacts;

    private Spot(Builder builder) {
        this.id = Objects.requireNonNull(builder.id, "id is required");
        this.ipAddress = builder.ipAddress;
        this.cpuLoad = builder.cpuLoad;
        this.runningTasks = builder.runningTasks;
        this.totalCores = builder.totalCores;
        this.ramUsedMb = builder.ramUsedMb;
        this.ramTotalMb = builder.ramTotalMb;
        this.maxConcurrent = builder.maxConcurrent;
        this.capabilitiesJson = builder.capabilitiesJson;
        this.labels = builder.labels;
        this.status = Objects.requireNonNull(builder.status, "status is required");
        this.lastHeartbeat = builder.lastHeartbeat;
        this.registeredAt = builder.registeredAt;
        this.hostname = builder.hostname;
        this.agentVersion = builder.agentVersion;
        this.osName = builder.osName;
        this.loadAvg1m = builder.loadAvg1m;
        this.swapUsedMb = builder.swapUsedMb;
        this.diskFreeGb = builder.diskFreeGb;
        this.jvmHeapUsedMb = builder.jvmHeapUsedMb;
        this.jvmHeapMaxMb = builder.jvmHeapMaxMb;
        this.cachedArtifacts = builder.cachedArtifacts;
    }

    // Getters
    public String id() {
        return id;
    }

    public String ipAddress() {
        return ipAddress;
    }

    public double cpuLoad() {
        return cpuLoad;
    }

    public int runningTasks() {
        return runningTasks;
    }

    public int totalCores() {
        return totalCores;
    }

    public long ramUsedMb() {
        return ramUsedMb;
    }

    public long ramTotalMb() {
        return ramTotalMb;
    }

    public int maxConcurrent() {
        return maxConcurrent;
    }

    public String capabilitiesJson() {
        return capabilitiesJson;
    }

    public String labels() {
        return labels;
    }

    public SpotStatus status() {
        return status;
    }

    public Instant lastHeartbeat() {
        return lastHeartbeat;
    }

    public Instant registeredAt() {
        return registeredAt;
    }

    public String hostname() { return hostname; }
    public String agentVersion() { return agentVersion; }
    public String osName() { return osName; }
    public double loadAvg1m() { return loadAvg1m; }
    public long swapUsedMb() { return swapUsedMb; }
    public double diskFreeGb() { return diskFreeGb; }
    public long jvmHeapUsedMb() { return jvmHeapUsedMb; }
    public long jvmHeapMaxMb() { return jvmHeapMaxMb; }
    public int cachedArtifacts() { return cachedArtifacts; }

    /** Check if SPOT is considered healthy (UP status) */
    public boolean isHealthy() {
        return status == SpotStatus.UP;
    }

    /** Create a builder from this spot (for updates) */
    public Builder toBuilder() {
        return new Builder()
                .id(id)
                .ipAddress(ipAddress)
                .cpuLoad(cpuLoad)
                .runningTasks(runningTasks)
                .totalCores(totalCores)
                .ramUsedMb(ramUsedMb)
                .ramTotalMb(ramTotalMb)
                .maxConcurrent(maxConcurrent)
                .capabilitiesJson(capabilitiesJson)
                .labels(labels)
                .status(status)
                .lastHeartbeat(lastHeartbeat)
                .registeredAt(registeredAt)
                .hostname(hostname)
                .agentVersion(agentVersion)
                .osName(osName)
                .loadAvg1m(loadAvg1m)
                .swapUsedMb(swapUsedMb)
                .diskFreeGb(diskFreeGb)
                .jvmHeapUsedMb(jvmHeapUsedMb)
                .jvmHeapMaxMb(jvmHeapMaxMb)
                .cachedArtifacts(cachedArtifacts);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String id;
        private String ipAddress;
        private double cpuLoad;
        private int runningTasks;
        private int totalCores;
        private long ramUsedMb;
        private long ramTotalMb;
        private int maxConcurrent;
        private String capabilitiesJson;
        private String labels;
        private SpotStatus status = SpotStatus.UP;
        private Instant lastHeartbeat;
        private Instant registeredAt;
        private String hostname;
        private String agentVersion;
        private String osName;
        private double loadAvg1m;
        private long swapUsedMb;
        private double diskFreeGb;
        private long jvmHeapUsedMb;
        private long jvmHeapMaxMb;
        private int cachedArtifacts;

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder ipAddress(String ipAddress) {
            this.ipAddress = ipAddress;
            return this;
        }

        public Builder cpuLoad(double cpuLoad) {
            this.cpuLoad = cpuLoad;
            return this;
        }

        public Builder runningTasks(int runningTasks) {
            this.runningTasks = runningTasks;
            return this;
        }

        public Builder totalCores(int totalCores) {
            this.totalCores = totalCores;
            return this;
        }

        public Builder ramUsedMb(long ramUsedMb) {
            this.ramUsedMb = ramUsedMb;
            return this;
        }

        public Builder ramTotalMb(long ramTotalMb) {
            this.ramTotalMb = ramTotalMb;
            return this;
        }

        public Builder maxConcurrent(int maxConcurrent) {
            this.maxConcurrent = maxConcurrent;
            return this;
        }

        public Builder capabilitiesJson(String capabilitiesJson) {
            this.capabilitiesJson = capabilitiesJson;
            return this;
        }

        public Builder labels(String labels) {
            this.labels = labels;
            return this;
        }

        public Builder status(SpotStatus status) {
            this.status = status;
            return this;
        }

        public Builder lastHeartbeat(Instant lastHeartbeat) {
            this.lastHeartbeat = lastHeartbeat;
            return this;
        }

        public Builder registeredAt(Instant registeredAt) {
            this.registeredAt = registeredAt;
            return this;
        }

        public Builder hostname(String hostname) { this.hostname = hostname; return this; }
        public Builder agentVersion(String agentVersion) { this.agentVersion = agentVersion; return this; }
        public Builder osName(String osName) { this.osName = osName; return this; }
        public Builder loadAvg1m(double loadAvg1m) { this.loadAvg1m = loadAvg1m; return this; }
        public Builder swapUsedMb(long swapUsedMb) { this.swapUsedMb = swapUsedMb; return this; }
        public Builder diskFreeGb(double diskFreeGb) { this.diskFreeGb = diskFreeGb; return this; }
        public Builder jvmHeapUsedMb(long jvmHeapUsedMb) { this.jvmHeapUsedMb = jvmHeapUsedMb; return this; }
        public Builder jvmHeapMaxMb(long jvmHeapMaxMb) { this.jvmHeapMaxMb = jvmHeapMaxMb; return this; }
        public Builder cachedArtifacts(int cachedArtifacts) { this.cachedArtifacts = cachedArtifacts; return this; }

        public Spot build() {
            return new Spot(this);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof Spot spot))
            return false;
        return Objects.equals(id, spot.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "Spot{id='" + id + "', status=" + status + ", cores=" + totalCores
                + ", ram=" + ramUsedMb + "/" + ramTotalMb + "MB}";
    }
}
