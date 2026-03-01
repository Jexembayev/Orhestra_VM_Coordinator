package orhestra.coordinator.ui;

/**
 * Per-spot task statistics for display in spot cards.
 */
public record SpotTaskStats(
        int running,
        int done,
        int failed) {
    public int total() {
        return running + done + failed;
    }

    public double avgRuntimeMs() {
        return 0;
    } // placeholder
}
