package orhestra.coordinator.service;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory blacklist: tracks (spotId, taskId) pairs where
 * a spot returned UNSUPPORTED for a task.
 * Prevents infinite claim→fail→claim loops.
 */
public final class SpotTaskBlacklist {

    // Key: "spotId:taskId"
    private final Set<String> blacklisted = ConcurrentHashMap.newKeySet();

    /** Record that this spot cannot execute this task */
    public void blacklist(String spotId, String taskId) {
        blacklisted.add(key(spotId, taskId));
    }

    /** Check if this spot is blacklisted for this task */
    public boolean isBlacklisted(String spotId, String taskId) {
        return blacklisted.contains(key(spotId, taskId));
    }

    /** Remove all blacklist entries for a task (e.g., when task is completed) */
    public void clearForTask(String taskId) {
        blacklisted.removeIf(k -> k.endsWith(":" + taskId));
    }

    /** Get count for monitoring */
    public int size() {
        return blacklisted.size();
    }

    private static String key(String spotId, String taskId) {
        return spotId + ":" + taskId;
    }
}
