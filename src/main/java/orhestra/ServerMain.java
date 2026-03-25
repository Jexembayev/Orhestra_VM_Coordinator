package orhestra;

import orhestra.coordinator.config.CoordinatorConfig;
import orhestra.coordinator.server.CoordinatorNettyServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Headless entry point for running the Coordinator on a server (VM/container).
 *
 * Does NOT start JavaFX. Reads config from environment variables and starts
 * only the Netty HTTP server.
 *
 * Usage:
 *   java -cp OrhestraV2-jar-with-dependencies.jar orhestra.ServerMain
 *
 * Key env vars:
 *   ORHESTRA_PORT    - HTTP port (default: 8081)
 *   ORHESTRA_DB_URL  - JDBC URL (default: H2 file ./data/orhestra)
 *
 * The server blocks the main thread until the JVM receives SIGTERM/SIGINT,
 * at which point it shuts down gracefully via the registered shutdown hook.
 */
public class ServerMain {

    private static final Logger log = LoggerFactory.getLogger(ServerMain.class);

    public static void main(String[] args) throws InterruptedException {
        log.info("=== Orhestra Coordinator — Server Mode ===");

        CoordinatorConfig config = CoordinatorConfig.fromEnv();
        int port = config.serverPort();

        log.info("Config: port={}, db={}", port, config.databaseUrl());

        // Start the server (wires all dependencies, starts scheduler, registers shutdown hook)
        boolean started = CoordinatorNettyServer.start(port, config);
        if (!started) {
            log.error("Failed to start server — exiting");
            System.exit(1);
        }

        log.info("Coordinator running on http://0.0.0.0:{}", port);
        log.info("Press Ctrl+C to stop.");

        // Block the main thread. The shutdown hook in CoordinatorNettyServer
        // will call CoordinatorNettyServer.stop() on JVM exit.
        Thread.currentThread().join();
    }
}
