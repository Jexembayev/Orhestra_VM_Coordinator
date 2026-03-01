package orhestra.coordinator.store;

import orhestra.coordinator.model.Spot;
import orhestra.coordinator.model.SpotStatus;
import orhestra.coordinator.repository.SpotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * JDBC implementation of SpotRepository.
 */
public class JdbcSpotRepository implements SpotRepository {

    private static final Logger log = LoggerFactory.getLogger(JdbcSpotRepository.class);

    private final Database db;
    private final AtomicInteger idGenerator = new AtomicInteger(1);

    public JdbcSpotRepository(Database db) {
        this.db = db;
        // Initialize ID generator from existing max ID
        initIdGenerator();
    }

    private void initIdGenerator() {
        String sql = "SELECT MAX(CAST(id AS INT)) FROM spots WHERE id NOT LIKE '%-%'";
        try (Connection conn = db.getConnection();
                Statement st = conn.createStatement();
                ResultSet rs = st.executeQuery(sql)) {
            if (rs.next()) {
                int maxId = rs.getInt(1);
                if (!rs.wasNull() && maxId > 0) {
                    idGenerator.set(maxId + 1);
                }
            }
        } catch (SQLException e) {
            // Ignore - might be empty or have non-numeric IDs
            log.debug("Could not initialize ID generator from DB: {}", e.getMessage());
        }
    }

    @Override
    public void save(Spot spot) {
        String sql = """
                    MERGE INTO spots (id, ip_address, cpu_load, running_tasks, total_cores, status, last_heartbeat, registered_at)
                    KEY (id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """;

        try (Connection conn = db.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, spot.id());
            ps.setString(2, spot.ipAddress());
            ps.setDouble(3, spot.cpuLoad());
            ps.setInt(4, spot.runningTasks());
            ps.setInt(5, spot.totalCores());
            ps.setString(6, spot.status().name());
            setTimestamp(ps, 7, spot.lastHeartbeat());
            setTimestamp(ps, 8, spot.registeredAt());

            ps.executeUpdate();
            conn.commit();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save spot: " + spot.id(), e);
        }
    }

    @Override
    public Optional<Spot> findById(String spotId) {
        String sql = "SELECT * FROM spots WHERE id = ?";

        try (Connection conn = db.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, spotId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
            }
            return Optional.empty();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find spot: " + spotId, e);
        }
    }

    @Override
    public List<Spot> findAll() {
        String sql = "SELECT * FROM spots ORDER BY last_heartbeat DESC";

        try (Connection conn = db.getConnection();
                Statement st = conn.createStatement();
                ResultSet rs = st.executeQuery(sql)) {

            return mapRows(rs);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find all spots", e);
        }
    }

    @Override
    public List<Spot> findByStatus(SpotStatus status) {
        String sql = "SELECT * FROM spots WHERE status = ? ORDER BY last_heartbeat DESC";

        try (Connection conn = db.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, status.name());
            try (ResultSet rs = ps.executeQuery()) {
                return mapRows(rs);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find spots by status: " + status, e);
        }
    }

    @Override
    public void heartbeat(String spotId, String ipAddress, double cpuLoad, int runningTasks, int totalCores,
            long ramUsedMb, long ramTotalMb) {
        // Use UPDATE + INSERT pattern (more portable than MERGE with subselect)
        String updateSql = """
                    UPDATE spots
                    SET ip_address = ?, cpu_load = ?, running_tasks = ?, total_cores = ?,
                        ram_used_mb = ?, ram_total_mb = ?,
                        status = 'UP', last_heartbeat = ?
                    WHERE id = ?
                """;

        String insertSql = """
                    INSERT INTO spots (id, ip_address, cpu_load, running_tasks, total_cores, ram_used_mb, ram_total_mb, status, last_heartbeat, registered_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 'UP', ?, ?)
                """;

        try (Connection conn = db.getConnection()) {
            Timestamp now = Timestamp.from(Instant.now());

            // Try UPDATE first
            try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
                ps.setString(1, ipAddress);
                ps.setDouble(2, cpuLoad);
                ps.setInt(3, runningTasks);
                ps.setInt(4, totalCores);
                ps.setLong(5, ramUsedMb);
                ps.setLong(6, ramTotalMb);
                ps.setTimestamp(7, now);
                ps.setString(8, spotId);

                int updated = ps.executeUpdate();

                if (updated == 0) {
                    // No existing record - INSERT new one
                    try (PreparedStatement insertPs = conn.prepareStatement(insertSql)) {
                        insertPs.setString(1, spotId);
                        insertPs.setString(2, ipAddress);
                        insertPs.setDouble(3, cpuLoad);
                        insertPs.setInt(4, runningTasks);
                        insertPs.setInt(5, totalCores);
                        insertPs.setLong(6, ramUsedMb);
                        insertPs.setLong(7, ramTotalMb);
                        insertPs.setTimestamp(8, now);
                        insertPs.setTimestamp(9, now);
                        insertPs.executeUpdate();
                    }
                }
            }

            conn.commit();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to update heartbeat for spot: " + spotId, e);
        }
    }

    @Override
    public List<String> markStaleAsDown(Instant lastHeartbeatBefore) {
        // First find the stale spots
        String selectSql = "SELECT id FROM spots WHERE last_heartbeat < ? AND status = 'UP'";
        String updateSql = "UPDATE spots SET status = 'DOWN' WHERE last_heartbeat < ? AND status = 'UP'";

        List<String> staleIds = new ArrayList<>();

        try (Connection conn = db.getConnection()) {
            // Get IDs first
            try (PreparedStatement ps = conn.prepareStatement(selectSql)) {
                ps.setTimestamp(1, Timestamp.from(lastHeartbeatBefore));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        staleIds.add(rs.getString("id"));
                    }
                }
            }

            // Then update
            if (!staleIds.isEmpty()) {
                try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
                    ps.setTimestamp(1, Timestamp.from(lastHeartbeatBefore));
                    ps.executeUpdate();
                }
                conn.commit();

                log.info("Marked {} spots as DOWN", staleIds.size());
            }

            return staleIds;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to mark stale spots as down", e);
        }
    }

    @Override
    public boolean delete(String spotId) {
        String sql = "DELETE FROM spots WHERE id = ?";

        try (Connection conn = db.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, spotId);
            int deleted = ps.executeUpdate();
            conn.commit();
            return deleted > 0;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete spot: " + spotId, e);
        }
    }

    @Override
    public int count() {
        String sql = "SELECT COUNT(*) FROM spots";

        try (Connection conn = db.getConnection();
                Statement st = conn.createStatement();
                ResultSet rs = st.executeQuery(sql)) {

            if (rs.next()) {
                return rs.getInt(1);
            }
            return 0;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to count spots", e);
        }
    }

    @Override
    public int countByStatus(SpotStatus status) {
        String sql = "SELECT COUNT(*) FROM spots WHERE status = ?";

        try (Connection conn = db.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, status.name());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
            return 0;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to count spots by status", e);
        }
    }

    @Override
    public String generateId() {
        return String.valueOf(idGenerator.getAndIncrement());
    }

    // Helper methods

    private List<Spot> mapRows(ResultSet rs) throws SQLException {
        List<Spot> results = new ArrayList<>();
        while (rs.next()) {
            results.add(mapRow(rs));
        }
        return results;
    }

    private Spot mapRow(ResultSet rs) throws SQLException {
        return Spot.builder()
                .id(rs.getString("id"))
                .ipAddress(rs.getString("ip_address"))
                .cpuLoad(rs.getDouble("cpu_load"))
                .runningTasks(rs.getInt("running_tasks"))
                .totalCores(rs.getInt("total_cores"))
                .ramUsedMb(rs.getLong("ram_used_mb"))
                .ramTotalMb(rs.getLong("ram_total_mb"))
                .status(SpotStatus.valueOf(rs.getString("status")))
                .lastHeartbeat(toInstant(rs.getTimestamp("last_heartbeat")))
                .registeredAt(toInstant(rs.getTimestamp("registered_at")))
                .build();
    }

    private static Instant toInstant(Timestamp ts) {
        return ts != null ? ts.toInstant() : null;
    }

    private static void setTimestamp(PreparedStatement ps, int index, Instant instant) throws SQLException {
        if (instant != null) {
            ps.setTimestamp(index, Timestamp.from(instant));
        } else {
            ps.setNull(index, Types.TIMESTAMP);
        }
    }
}
