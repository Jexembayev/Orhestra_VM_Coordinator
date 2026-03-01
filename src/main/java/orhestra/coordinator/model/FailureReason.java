package orhestra.coordinator.model;

/**
 * Failure reasons for task execution.
 * These control retry and reassignment behavior.
 */
public enum FailureReason {

    /**
     * Spot cannot execute this task spec at all (wrong
     * optimizer/algorithm/function).
     * Coordinator must NOT reassign to the same spot.
     */
    UNSUPPORTED,

    /**
     * Spot claims capability but artifact is missing on disk.
     * Coordinator should mark spot as degraded for this optimizer.
     */
    MISSING_ARTIFACT,

    /**
     * Process exited with non-zero code.
     * Normal retry policy applies (max attempts).
     */
    RUNTIME_ERROR,

    /**
     * Task exceeded time limit.
     * Normal retry policy applies (max attempts).
     */
    TIMEOUT;

    /** Parse from string, defaulting to RUNTIME_ERROR for unknown values. */
    public static FailureReason fromString(String s) {
        if (s == null || s.isBlank())
            return RUNTIME_ERROR;
        try {
            return valueOf(s.toUpperCase());
        } catch (IllegalArgumentException e) {
            return RUNTIME_ERROR;
        }
    }

    /** Whether this failure should count toward max attempts. */
    public boolean countsAsAttempt() {
        return this == RUNTIME_ERROR || this == TIMEOUT;
    }

    /** Whether the task should be re-queued for a different spot. */
    public boolean shouldRequeue() {
        return this == UNSUPPORTED || this == MISSING_ARTIFACT;
    }
}
