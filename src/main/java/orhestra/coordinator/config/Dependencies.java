package orhestra.coordinator.config;

import orhestra.coordinator.api.internal.v1.HeartbeatController;
import orhestra.coordinator.api.internal.v1.TaskController;
import orhestra.coordinator.api.v1.AdminConfigController;
import orhestra.coordinator.api.v1.AdminSpotsController;
import orhestra.coordinator.api.v1.HealthController;
import orhestra.coordinator.api.v1.JobController;
import orhestra.coordinator.api.v1.ParameterSchemaController;
import orhestra.coordinator.api.v1.SpotController;
import orhestra.coordinator.repository.JobRepository;
import orhestra.coordinator.repository.SpotRepository;
import orhestra.coordinator.repository.TaskRepository;
import orhestra.coordinator.scheduler.Scheduler;
import orhestra.coordinator.server.RouterHandler;
import orhestra.coordinator.service.ConfigService;
import orhestra.coordinator.service.JobService;
import orhestra.coordinator.service.SpotService;
import orhestra.coordinator.service.SpotTaskBlacklist;
import orhestra.coordinator.service.TaskService;
import orhestra.coordinator.store.Database;
import orhestra.coordinator.store.JdbcJobRepository;
import orhestra.coordinator.store.JdbcSpotRepository;
import orhestra.coordinator.store.JdbcTaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manual dependency injection container.
 * Creates and wires all service dependencies.
 * 
 * Usage:
 * 
 * <pre>
 * Dependencies deps = Dependencies.create(CoordinatorConfig.fromEnv());
 * deps.startScheduler(); // start background tasks
 * TaskService taskService = deps.taskService();
 * // ... use services ...
 * deps.close(); // cleanup
 * </pre>
 */
public final class Dependencies implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Dependencies.class);

    private final CoordinatorConfig config;
    private final Database database;
    private final TaskRepository taskRepository;
    private final SpotRepository spotRepository;
    private final JobRepository jobRepository;
    private final SpotTaskBlacklist blacklist;
    private final TaskService taskService;
    private final SpotService spotService;
    private final JobService jobService;

    // Controllers
    private final HealthController healthController;
    private final SpotController spotController;
    private final JobController jobController;
    private final ParameterSchemaController parameterSchemaController;
    private final HeartbeatController heartbeatController;
    private final TaskController taskController;
    private final AdminConfigController adminConfigController;
    private final AdminSpotsController adminSpotsController;

    // Config service (cloud config / VM creation)
    private final ConfigService configService;

    // Router (lazy-initialized)
    private RouterHandler routerHandler;

    // Scheduler (lazy-initialized)
    private Scheduler scheduler;

    private Dependencies(CoordinatorConfig config) {
        this.config = config;

        log.info("Initializing dependencies with config: {}", config);

        // Infrastructure
        this.database = new Database(config);

        // Repositories
        this.taskRepository = new JdbcTaskRepository(database);
        this.spotRepository = new JdbcSpotRepository(database);
        this.jobRepository = new JdbcJobRepository(database);

        // Services
        this.blacklist = new SpotTaskBlacklist();
        this.taskService = new TaskService(taskRepository, spotRepository, blacklist, config);
        this.spotService = new SpotService(spotRepository, taskRepository, config);
        this.jobService = new JobService(jobRepository, taskRepository, config);

        // Controllers (public API)
        this.healthController = new HealthController(database, spotService, taskService);
        this.spotController = new SpotController(spotService);
        this.jobController = new JobController(jobService);
        this.parameterSchemaController = new ParameterSchemaController();

        // Controllers (internal API)
        this.heartbeatController = new HeartbeatController(spotService);
        this.taskController = new TaskController(taskService);

        // Admin / cloud management
        this.configService = new ConfigService();
        this.adminConfigController = new AdminConfigController(configService);
        this.adminSpotsController  = new AdminSpotsController(configService);

        log.info("Dependencies initialized successfully");
    }

    /**
     * Create dependencies with the given config.
     */
    public static Dependencies create(CoordinatorConfig config) {
        return new Dependencies(config);
    }

    /**
     * Create dependencies with environment-based config.
     */
    public static Dependencies create() {
        return create(CoordinatorConfig.fromEnv());
    }

    // Getters
    public CoordinatorConfig config() {
        return config;
    }

    public Database database() {
        return database;
    }

    public TaskRepository taskRepository() {
        return taskRepository;
    }

    public SpotRepository spotRepository() {
        return spotRepository;
    }

    public JobRepository jobRepository() {
        return jobRepository;
    }

    public TaskService taskService() {
        return taskService;
    }

    public SpotService spotService() {
        return spotService;
    }

    public JobService jobService() {
        return jobService;
    }

    // Controller getters
    public HealthController healthController() {
        return healthController;
    }

    public SpotController spotController() {
        return spotController;
    }

    public JobController jobController() {
        return jobController;
    }

    public ParameterSchemaController parameterSchemaController() {
        return parameterSchemaController;
    }

    public HeartbeatController heartbeatController() {
        return heartbeatController;
    }

    public TaskController taskController() {
        return taskController;
    }

    /**
     * Get a fully configured RouterHandler with all controllers registered.
     * This is the main entry point for serving HTTP requests.
     */
    public RouterHandler routerHandler() {
        if (routerHandler == null) {
            routerHandler = new RouterHandler(config)
                    .registerController(healthController)
                    .registerController(spotController)
                    .registerController(jobController)
                    .registerController(parameterSchemaController)
                    .registerController(adminConfigController)
                    .registerController(adminSpotsController)
                    .registerController(heartbeatController)
                    .registerController(taskController);
            log.info("RouterHandler created with {} controllers", 8);
        }
        return routerHandler;
    }

    /**
     * Get the scheduler (creates it if not yet created).
     */
    public Scheduler scheduler() {
        if (scheduler == null) {
            scheduler = new Scheduler(taskRepository, spotService::reapStaleSpots, config);
        }
        return scheduler;
    }

    /**
     * Start the background scheduler for task reaping and spot cleanup.
     * Should be called after server startup.
     */
    public void startScheduler() {
        scheduler().start();
    }

    /**
     * Stop the background scheduler.
     */
    public void stopScheduler() {
        if (scheduler != null) {
            scheduler.stop();
        }
    }

    @Override
    public void close() {
        log.info("Closing dependencies...");

        // Stop scheduler first
        if (scheduler != null) {
            try {
                scheduler.stop();
            } catch (Exception e) {
                log.warn("Error stopping scheduler: {}", e.getMessage());
            }
        }

        // Close database
        try {
            database.close();
        } catch (Exception e) {
            log.warn("Error closing database: {}", e.getMessage());
        }

        log.info("Dependencies closed");
    }
}
