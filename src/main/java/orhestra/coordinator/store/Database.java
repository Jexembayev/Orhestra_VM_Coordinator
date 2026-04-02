package orhestra.coordinator.store;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import orhestra.coordinator.config.CoordinatorConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Database connection pool and schema management.
 * Uses HikariCP for connection pooling.
 */
public final class Database implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Database.class);

    private final HikariDataSource dataSource;

    public Database(CoordinatorConfig config) {
        this(config.databaseUrl(), config.databasePoolSize());
    }

    public Database(String jdbcUrl, int poolSize) {
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(jdbcUrl);
        hikariConfig.setMaximumPoolSize(poolSize);
        hikariConfig.setMinimumIdle(2);
        hikariConfig.setConnectionTimeout(5000);
        hikariConfig.setIdleTimeout(300000);
        hikariConfig.setPoolName("orhestra-db-pool");
        hikariConfig.setAutoCommit(false);

        // H2 specific settings
        if (jdbcUrl.contains("h2:")) {
            hikariConfig.addDataSourceProperty("MODE", "PostgreSQL");
        }

        this.dataSource = new HikariDataSource(hikariConfig);

        log.info("Database pool initialized: {}", jdbcUrl);

        // Initialize schema
        initSchema();
    }

    /**
     * Get a connection from the pool.
     * Caller is responsible for closing the connection.
     */
    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    /**
     * Get the underlying DataSource (for frameworks that need it).
     */
    public DataSource getDataSource() {
        return dataSource;
    }

    /**
     * Check if database is healthy.
     */
    public boolean isHealthy() {
        try (Connection conn = getConnection()) {
            return conn.isValid(2);
        } catch (SQLException e) {
            log.warn("Database health check failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Initialize database schema.
     */
    private void initSchema() {
        try (Connection conn = getConnection();
                Statement st = conn.createStatement()) {

            // ---------- JOBS ----------
            st.addBatch("""
                        CREATE TABLE IF NOT EXISTS jobs (
                            id                VARCHAR(64) PRIMARY KEY,
                            jar_path          VARCHAR(1024),
                            artifact_bucket   VARCHAR(256),
                            artifact_key      VARCHAR(1024),
                            artifact_endpoint VARCHAR(512),
                            main_class        VARCHAR(256) NOT NULL,
                            config            CLOB NOT NULL,
                            status            VARCHAR(20) DEFAULT 'PENDING',
                            total_tasks       INT DEFAULT 0,
                            completed_tasks   INT DEFAULT 0,
                            failed_tasks      INT DEFAULT 0,
                            created_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                            started_at        TIMESTAMP,
                            finished_at       TIMESTAMP
                        );
                    """);

            // ---------- TASKS ----------
            st.addBatch("""
                        CREATE TABLE IF NOT EXISTS tasks (
                            id              VARCHAR(64) PRIMARY KEY,
                            job_id          VARCHAR(64),
                            payload         CLOB NOT NULL,
                            status          VARCHAR(20) DEFAULT 'NEW',
                            assigned_to     VARCHAR(64),
                            priority        INT DEFAULT 0,
                            attempts        INT DEFAULT 0,
                            max_attempts    INT DEFAULT 3,
                            error_message   VARCHAR(2048),
                            created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                            started_at      TIMESTAMP,
                            finished_at     TIMESTAMP,
                            runtime_ms      BIGINT,
                            iter            INT,
                            fopt            DOUBLE,
                            result          CLOB,
                            algorithm       VARCHAR(128),
                            optimizer_id    VARCHAR(128),
                            function        VARCHAR(128),
                            input_iterations INT,
                            input_agents     INT,
                            input_dimension  INT
                        );
                    """);

            // ---------- SPOTS ----------
            st.addBatch("""
                        CREATE TABLE IF NOT EXISTS spots (
                            id              VARCHAR(64) PRIMARY KEY,
                            ip_address      VARCHAR(64),
                            cpu_load        DOUBLE,
                            running_tasks   INT DEFAULT 0,
                            total_cores     INT DEFAULT 0,
                            ram_used_mb     BIGINT DEFAULT 0,
                            ram_total_mb    BIGINT DEFAULT 0,
                            max_concurrent  INT DEFAULT 0,
                            capabilities_json CLOB,
                            labels          VARCHAR(512),
                            status          VARCHAR(20) DEFAULT 'UP',
                            last_heartbeat  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                            registered_at   TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                        );
                    """);

            // Migrate: add columns for existing databases
            st.addBatch("ALTER TABLE tasks ADD COLUMN IF NOT EXISTS algorithm VARCHAR(128);");
            st.addBatch("ALTER TABLE tasks ADD COLUMN IF NOT EXISTS optimizer_id VARCHAR(128);");
            st.addBatch("ALTER TABLE tasks ADD COLUMN IF NOT EXISTS function VARCHAR(128);");
            st.addBatch("ALTER TABLE tasks ADD COLUMN IF NOT EXISTS input_iterations INT;");
            st.addBatch("ALTER TABLE tasks ADD COLUMN IF NOT EXISTS input_agents INT;");
            st.addBatch("ALTER TABLE tasks ADD COLUMN IF NOT EXISTS input_dimension INT;");
            st.addBatch("ALTER TABLE tasks ADD COLUMN IF NOT EXISTS main_class VARCHAR(256);");
            st.addBatch("ALTER TABLE spots ADD COLUMN IF NOT EXISTS ram_used_mb BIGINT DEFAULT 0;");
            st.addBatch("ALTER TABLE spots ADD COLUMN IF NOT EXISTS ram_total_mb BIGINT DEFAULT 0;");
            st.addBatch("ALTER TABLE spots ADD COLUMN IF NOT EXISTS max_concurrent INT DEFAULT 0;");
            st.addBatch("ALTER TABLE spots ADD COLUMN IF NOT EXISTS capabilities_json CLOB;");
            st.addBatch("ALTER TABLE spots ADD COLUMN IF NOT EXISTS labels VARCHAR(512);");
            // agent v2.2+ fields
            st.addBatch("ALTER TABLE spots ADD COLUMN IF NOT EXISTS hostname VARCHAR(256);");
            st.addBatch("ALTER TABLE spots ADD COLUMN IF NOT EXISTS agent_version VARCHAR(32);");
            st.addBatch("ALTER TABLE spots ADD COLUMN IF NOT EXISTS os_name VARCHAR(128);");
            st.addBatch("ALTER TABLE spots ADD COLUMN IF NOT EXISTS load_avg_1m DOUBLE DEFAULT 0;");
            st.addBatch("ALTER TABLE spots ADD COLUMN IF NOT EXISTS swap_used_mb BIGINT DEFAULT 0;");
            st.addBatch("ALTER TABLE spots ADD COLUMN IF NOT EXISTS disk_free_gb DOUBLE DEFAULT 0;");
            st.addBatch("ALTER TABLE spots ADD COLUMN IF NOT EXISTS jvm_heap_used_mb BIGINT DEFAULT 0;");
            st.addBatch("ALTER TABLE spots ADD COLUMN IF NOT EXISTS jvm_heap_max_mb BIGINT DEFAULT 0;");
            st.addBatch("ALTER TABLE spots ADD COLUMN IF NOT EXISTS cached_artifacts INT DEFAULT 0;");
            // task v2.2+ fields
            st.addBatch("ALTER TABLE tasks ADD COLUMN IF NOT EXISTS peak_ram_mb BIGINT;");
            st.addBatch("ALTER TABLE tasks ADD COLUMN IF NOT EXISTS exit_code INT;");
            st.addBatch("ALTER TABLE tasks ADD COLUMN IF NOT EXISTS output_snippet VARCHAR(512);");
            // S3 artifact columns on jobs
            st.addBatch("ALTER TABLE jobs ADD COLUMN IF NOT EXISTS artifact_bucket   VARCHAR(256);");
            st.addBatch("ALTER TABLE jobs ADD COLUMN IF NOT EXISTS artifact_key      VARCHAR(1024);");
            st.addBatch("ALTER TABLE jobs ADD COLUMN IF NOT EXISTS artifact_endpoint VARCHAR(512);");
            // jar_path was NOT NULL — make nullable for migration (artifact fields are the source of truth)
            st.addBatch("ALTER TABLE jobs ALTER COLUMN jar_path DROP NOT NULL;");

            // Indexes
            st.addBatch(
                    "CREATE INDEX IF NOT EXISTS idx_tasks_status_priority ON tasks(status, priority DESC, created_at);");
            st.addBatch("CREATE INDEX IF NOT EXISTS idx_tasks_job_status ON tasks(job_id, status);");
            st.addBatch("CREATE INDEX IF NOT EXISTS idx_tasks_assigned_running ON tasks(assigned_to, status);");
            st.addBatch("CREATE INDEX IF NOT EXISTS idx_spots_heartbeat ON spots(last_heartbeat);");
            st.addBatch("CREATE INDEX IF NOT EXISTS idx_jobs_status ON jobs(status);");

            st.executeBatch();
            conn.commit();

            log.info("Database schema initialized");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to initialize database schema", e);
        }

        // Wipe all existing data on startup (clean slate)
        wipeDataOnStartup();
    }

    /**
     * Wipe ALL data from database tables on startup.
     * Schema (tables, indexes) remains, only rows are deleted.
     * Runs in a single transaction, fail-fast on error.
     */
    private void wipeDataOnStartup() {
        try (Connection conn = getConnection();
                Statement st = conn.createStatement()) {

            // Delete in order that respects FK relationships:
            // tasks -> spots -> jobs (tasks reference jobs)
            // Using DELETE FROM instead of TRUNCATE for H2 PostgreSQL mode compatibility
            st.addBatch("DELETE FROM tasks");
            st.addBatch("DELETE FROM spots");
            st.addBatch("DELETE FROM jobs");

            st.executeBatch();
            conn.commit();

            log.info("DB wipe on startup: OK");
            System.out.println("DB wipe on startup: OK");

        } catch (SQLException e) {
            // FAIL-FAST: Print full stack trace and throw to prevent server start
            System.err.println("DB wipe on startup: FAILED");
            e.printStackTrace(System.err);
            throw new RuntimeException("Failed to wipe database on startup - server cannot start", e);
        }
    }

    @Override
    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            log.info("Database pool closed");
        }
    }
}
