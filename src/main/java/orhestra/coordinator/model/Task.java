package orhestra.coordinator.model;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable domain model representing a computational task.
 * This is the core business entity - use records for data transfer.
 */
public final class Task {
    private final String id;
    private final String jobId;
    private final String payload; // JSON with algorithm params
    private final TaskStatus status;
    private final String assignedTo; // SPOT ID or null
    private final int priority;
    private final int attempts;
    private final int maxAttempts;
    private final String errorMessage;
    private final Instant createdAt;
    private final Instant startedAt;
    private final Instant finishedAt;
    private final Long runtimeMs;
    private final Integer iter;
    private final Double fopt;
    private final String result; // Full result JSON
    // Input parameters (extracted from payload for DB querying)
    private final String algorithm;
    private final String optimizerId;
    private final String function;
    private final Integer inputIterations;
    private final Integer inputAgents;
    private final Integer inputDimension;

    private Task(Builder builder) {
        this.id = Objects.requireNonNull(builder.id, "id is required");
        this.jobId = builder.jobId;
        this.payload = Objects.requireNonNull(builder.payload, "payload is required");
        this.status = Objects.requireNonNull(builder.status, "status is required");
        this.assignedTo = builder.assignedTo;
        this.priority = builder.priority;
        this.attempts = builder.attempts;
        this.maxAttempts = builder.maxAttempts;
        this.errorMessage = builder.errorMessage;
        this.createdAt = builder.createdAt;
        this.startedAt = builder.startedAt;
        this.finishedAt = builder.finishedAt;
        this.runtimeMs = builder.runtimeMs;
        this.iter = builder.iter;
        this.fopt = builder.fopt;
        this.result = builder.result;
        this.algorithm = builder.algorithm;
        this.optimizerId = builder.optimizerId;
        this.function = builder.function;
        this.inputIterations = builder.inputIterations;
        this.inputAgents = builder.inputAgents;
        this.inputDimension = builder.inputDimension;
    }

    // Getters
    public String id() {
        return id;
    }

    public String jobId() {
        return jobId;
    }

    public String payload() {
        return payload;
    }

    public TaskStatus status() {
        return status;
    }

    public String assignedTo() {
        return assignedTo;
    }

    public int priority() {
        return priority;
    }

    public int attempts() {
        return attempts;
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    public String errorMessage() {
        return errorMessage;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public Instant finishedAt() {
        return finishedAt;
    }

    public Long runtimeMs() {
        return runtimeMs;
    }

    public Integer iter() {
        return iter;
    }

    public Double fopt() {
        return fopt;
    }

    public String result() {
        return result;
    }

    public String algorithm() {
        return algorithm;
    }

    public String optimizerId() {
        return optimizerId;
    }

    public String function() {
        return function;
    }

    public Integer inputIterations() {
        return inputIterations;
    }

    public Integer inputAgents() {
        return inputAgents;
    }

    public Integer inputDimension() {
        return inputDimension;
    }

    /** Check if task can be retried */
    public boolean canRetry() {
        return attempts < maxAttempts;
    }

    /** Check if task is in terminal state */
    public boolean isTerminal() {
        return status == TaskStatus.DONE ||
                status == TaskStatus.FAILED ||
                status == TaskStatus.CANCELLED;
    }

    /** Create a builder from this task (for updates) */
    public Builder toBuilder() {
        return new Builder()
                .id(id)
                .jobId(jobId)
                .payload(payload)
                .status(status)
                .assignedTo(assignedTo)
                .priority(priority)
                .attempts(attempts)
                .maxAttempts(maxAttempts)
                .errorMessage(errorMessage)
                .createdAt(createdAt)
                .startedAt(startedAt)
                .finishedAt(finishedAt)
                .runtimeMs(runtimeMs)
                .iter(iter)
                .fopt(fopt)
                .result(result)
                .algorithm(algorithm)
                .optimizerId(optimizerId)
                .function(function)
                .inputIterations(inputIterations)
                .inputAgents(inputAgents)
                .inputDimension(inputDimension);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String id;
        private String jobId;
        private String payload;
        private TaskStatus status = TaskStatus.NEW;
        private String assignedTo;
        private int priority = 0;
        private int attempts = 0;
        private int maxAttempts = 3;
        private String errorMessage;
        private Instant createdAt;
        private Instant startedAt;
        private Instant finishedAt;
        private Long runtimeMs;
        private Integer iter;
        private Double fopt;
        private String result;
        private String algorithm;
        private String optimizerId;
        private String function;
        private Integer inputIterations;
        private Integer inputAgents;
        private Integer inputDimension;

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder jobId(String jobId) {
            this.jobId = jobId;
            return this;
        }

        public Builder payload(String payload) {
            this.payload = payload;
            return this;
        }

        public Builder status(TaskStatus status) {
            this.status = status;
            return this;
        }

        public Builder assignedTo(String assignedTo) {
            this.assignedTo = assignedTo;
            return this;
        }

        public Builder priority(int priority) {
            this.priority = priority;
            return this;
        }

        public Builder attempts(int attempts) {
            this.attempts = attempts;
            return this;
        }

        public Builder maxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
            return this;
        }

        public Builder errorMessage(String errorMessage) {
            this.errorMessage = errorMessage;
            return this;
        }

        public Builder createdAt(Instant createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public Builder startedAt(Instant startedAt) {
            this.startedAt = startedAt;
            return this;
        }

        public Builder finishedAt(Instant finishedAt) {
            this.finishedAt = finishedAt;
            return this;
        }

        public Builder runtimeMs(Long runtimeMs) {
            this.runtimeMs = runtimeMs;
            return this;
        }

        public Builder iter(Integer iter) {
            this.iter = iter;
            return this;
        }

        public Builder fopt(Double fopt) {
            this.fopt = fopt;
            return this;
        }

        public Builder result(String result) {
            this.result = result;
            return this;
        }

        public Builder algorithm(String algorithm) {
            this.algorithm = algorithm;
            return this;
        }

        public Builder optimizerId(String optimizerId) {
            this.optimizerId = optimizerId;
            return this;
        }

        public Builder function(String function) {
            this.function = function;
            return this;
        }

        public Builder inputIterations(Integer inputIterations) {
            this.inputIterations = inputIterations;
            return this;
        }

        public Builder inputAgents(Integer inputAgents) {
            this.inputAgents = inputAgents;
            return this;
        }

        public Builder inputDimension(Integer inputDimension) {
            this.inputDimension = inputDimension;
            return this;
        }

        public Task build() {
            return new Task(this);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof Task task))
            return false;
        return Objects.equals(id, task.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "Task{id='" + id + "', status=" + status + ", assignedTo='" + assignedTo + "'}";
    }
}
