package orhestra.coordinator.server;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.ChannelMatchers;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.concurrent.GlobalEventExecutor;
import orhestra.coordinator.config.CoordinatorConfig;
import orhestra.coordinator.config.Dependencies;
import orhestra.coordinator.service.AutoScaler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;

/**
 * Netty-based HTTP server for the Coordinator.
 * 
 * Uses the new dependency injection container and versioned routing system.
 * All requests are handled by RouterHandler with registered Controllers.
 */
public final class CoordinatorNettyServer {

    private static final Logger log = LoggerFactory.getLogger(CoordinatorNettyServer.class);

    // ========== Dependency Container ==========
    private static volatile Dependencies dependencies;

    // ========== Auto-Scaler ==========
    private static volatile AutoScaler autoScaler;

    public static AutoScaler autoScaler() { return autoScaler; }

    public static void setAutoScaler(AutoScaler scaler) { autoScaler = scaler; }

    // ========== Server State ==========
    private static volatile boolean running = false;
    private static Channel serverChannel;
    private static EventLoopGroup bossGroup;
    private static EventLoopGroup workerGroup;

    static final ChannelGroup CLIENTS = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);

    // ========== Metrics ==========
    static final LongAdder HB_COUNT = new LongAdder();
    static final LongAdder GET_TASK_COUNT = new LongAdder();

    // ========== Logging ==========
    private static volatile Consumer<String> logger = null;

    public static void setLogger(Consumer<String> l) {
        logger = l;
    }

    static void log(String s) {
        var l = logger;
        if (l != null)
            l.accept(s + "\n");
        log.info(s);
    }

    private CoordinatorNettyServer() {
    }

    /**
     * Get the dependencies container.
     * Available after server starts.
     * 
     * @throws IllegalStateException if server not started
     */
    public static Dependencies dependencies() {
        Dependencies deps = dependencies;
        if (deps == null) {
            throw new IllegalStateException("Server not started - dependencies not available");
        }
        return deps;
    }

    /**
     * Try to get the dependencies container without throwing.
     * Returns null if server not started yet.
     * Use this from UI controllers that need to be resilient to startup timing.
     * 
     * @return dependencies or null if not yet available
     */
    public static Dependencies tryDependencies() {
        return dependencies;
    }

    /**
     * Check if dependencies are available.
     */
    public static boolean hasDependencies() {
        return dependencies != null;
    }

    /**
     * Create the HTTP pipeline with the RouterHandler.
     * 
     * @throws IllegalStateException if dependencies not initialized
     */
    public static ChannelHandler pipelineInitializer(Consumer<String> logSink) {
        setLogger(logSink);
        return new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(SocketChannel ch) {
                if (dependencies == null) {
                    throw new IllegalStateException("Dependencies not initialized - server cannot handle requests");
                }

                ChannelPipeline p = ch.pipeline();
                p.addLast(new IdleStateHandler(60, 0, 0, TimeUnit.SECONDS));
                p.addLast(new HttpServerCodec());
                p.addLast(new HttpObjectAggregator(1 * 1024 * 1024));
                p.addLast(dependencies.routerHandler());
            }
        };
    }

    /**
     * Start the server with default configuration.
     */
    public static synchronized boolean start(int port) {
        return start(port, CoordinatorConfig.fromEnv());
    }

    /**
     * Start the server with custom configuration.
     * 
     * @throws RuntimeException if dependencies cannot be initialized
     */
    public static synchronized boolean start(int port, CoordinatorConfig config) {
        if (running)
            return true;

        try {
            log("Initializing Coordinator server...");

            // Initialize dependencies FIRST (fail-fast if this fails)
            dependencies = Dependencies.create(config);
            log("Dependencies initialized");

            // Setup Netty
            bossGroup = new NioEventLoopGroup(1);
            workerGroup = new NioEventLoopGroup();

            ServerBootstrap b = new ServerBootstrap()
                    .group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childHandler(pipelineInitializer(logger));

            serverChannel = b.bind(port).syncUninterruptibly().channel();
            running = true;
            log("Coordinator HTTP server started on port " + port);

            // Start background scheduler (task reaper, spot cleanup)
            dependencies.startScheduler();
            log("Background scheduler started");

            // Periodic stats logging (every second)
            workerGroup.next().scheduleAtFixedRate(() -> {
                int activeSpots = dependencies.spotService().countActive();
                long hb = HB_COUNT.sumThenReset();
                long gt = GET_TASK_COUNT.sumThenReset();
                if (hb > 0 || gt > 0) {
                    log("stats: hb/s=" + hb + " claim/s=" + gt + " active_spots=" + activeSpots);
                }
            }, 1, 1, TimeUnit.SECONDS);

            // Register shutdown hook
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                log("Shutdown hook triggered");
                stop();
            }, "coordinator-shutdown"));

            log("Coordinator fully started on port " + port);
            return true;

        } catch (Throwable t) {
            log("Start error: " + t.getMessage());
            log.error("Failed to start coordinator", t);
            stop();
            throw new RuntimeException("Failed to start coordinator server", t);
        }
    }

    /**
     * Stop the server gracefully.
     */
    public static synchronized void stop() {
        if (!running)
            return;

        log("Stopping Coordinator server...");

        try {
            // Stop scheduler first
            if (dependencies != null) {
                dependencies.stopScheduler();
                log("Scheduler stopped");
            }

            // Close server channel
            if (serverChannel != null) {
                serverChannel.close().syncUninterruptibly();
                serverChannel = null;
            }

            // Close client connections
            CLIENTS.close().awaitUninterruptibly();

        } finally {
            // Shutdown Netty
            if (workerGroup != null) {
                workerGroup.shutdownGracefully();
                workerGroup = null;
            }
            if (bossGroup != null) {
                bossGroup.shutdownGracefully();
                bossGroup = null;
            }

            // Stop auto-scaler
            if (autoScaler != null) {
                autoScaler.stop();
                autoScaler = null;
            }

            // Close dependencies (database connections)
            if (dependencies != null) {
                dependencies.close();
                dependencies = null;
                log("Dependencies closed");
            }

            running = false;
            log("Coordinator stopped");
        }
    }

    public static boolean isRunning() {
        return running;
    }

    public static void broadcast(String line) {
        if (!running)
            return;
        CLIENTS.writeAndFlush(Objects.requireNonNull(line) + "\n", ChannelMatchers.isNot(serverChannel), true);
    }
}
