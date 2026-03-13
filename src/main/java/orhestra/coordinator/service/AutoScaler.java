package orhestra.coordinator.service;

import orhestra.cloud.auth.AuthService;
import orhestra.cloud.config.IniLoader;
import orhestra.cloud.creator.VMCreator;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Watches the pending-task queue. If tasks are waiting without a SPOT
 * picking them up for THRESHOLD_SEC seconds, automatically creates 1 new SPOT VM.
 *
 * After creation a COOLDOWN_SEC cooldown prevents immediately spawning more,
 * giving the new VM time to boot and register with the coordinator.
 */
public class AutoScaler {

    public enum Phase { IDLE, WAITING, CREATING, COOLDOWN }

    public record State(
            int    pendingTasks,
            int    waitedSec,
            int    thresholdSec,
            Phase  phase,
            String message) {}

    private static final int THRESHOLD_SEC = 15;
    private static final int COOLDOWN_SEC  = 90; // VM boot + agent register time

    // Services
    private final TaskService taskService;

    // Cloud config — set by CloudController after INI load + coordinator start
    private volatile AuthService        authService;
    private volatile IniLoader.VmConfig vmConfig;
    private volatile String             coordinatorUrl;
    private volatile boolean            configured = false;

    // State
    private final AtomicLong pendingSince  = new AtomicLong(-1);
    private volatile long    lastCreatedAt = 0;
    private volatile Phase   phase         = Phase.IDLE;

    private final ScheduledExecutorService executor;
    private volatile Consumer<State>       uiCallback;

    public AutoScaler(TaskService taskService) {
        this.taskService = taskService;
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "auto-scaler");
            t.setDaemon(true);
            return t;
        });
    }

    // ── Configuration ────────────────────────────────────────────

    /** Called by CloudController once INI is loaded and coordinator is running. */
    public void configure(AuthService auth, IniLoader.VmConfig config, String coordUrl) {
        this.authService    = auth;
        this.vmConfig       = config;
        this.coordinatorUrl = coordUrl;
        this.configured     = true;
    }

    public void setUiCallback(Consumer<State> callback) {
        this.uiCallback = callback;
    }

    public boolean isConfigured() { return configured; }

    // ── Lifecycle ────────────────────────────────────────────────

    public void start() {
        executor.scheduleAtFixedRate(this::tick, 1, 1, TimeUnit.SECONDS);
    }

    public void stop() {
        executor.shutdown();
    }

    // ── Tick (runs every 1 second) ───────────────────────────────

    private void tick() {
        try {
            int  pending = taskService.countPending();
            long now     = System.currentTimeMillis();

            // During cooldown — VM is starting, just count down
            if (phase == Phase.CREATING || phase == Phase.COOLDOWN) {
                int remaining = (int) ((lastCreatedAt + COOLDOWN_SEC * 1000L - now) / 1000);
                if (remaining > 0) {
                    notifyUi(new State(pending, 0, THRESHOLD_SEC, Phase.COOLDOWN,
                            "✅ SPOT создаётся... ≈" + remaining + "с до регистрации"));
                    return;
                }
                // Cooldown over — reset
                phase = Phase.IDLE;
                pendingSince.set(-1);
            }

            // No pending tasks — all quiet
            if (pending == 0) {
                pendingSince.set(-1);
                phase = Phase.IDLE;
                notifyUi(new State(0, 0, THRESHOLD_SEC, Phase.IDLE, ""));
                return;
            }

            // Pending tasks exist — start or continue the waiting timer
            long since = pendingSince.get();
            if (since < 0) {
                pendingSince.set(now);
                since = now;
            }

            int waitedSec = (int) ((now - since) / 1000);

            if (waitedSec < THRESHOLD_SEC) {
                phase = Phase.WAITING;
                notifyUi(new State(pending, waitedSec, THRESHOLD_SEC, Phase.WAITING,
                        "Задачи ждут SPOT: " + waitedSec + "с / " + THRESHOLD_SEC + "с"));
                return;
            }

            // ── Threshold exceeded → create 1 SPOT ──────────────
            if (!configured || authService == null || vmConfig == null) {
                notifyUi(new State(pending, waitedSec, THRESHOLD_SEC, Phase.WAITING,
                        "⚠ Загрузите INI и запустите координатор для автосоздания SPOT"));
                return;
            }

            phase = Phase.CREATING;
            lastCreatedAt = now;
            pendingSince.set(-1);
            notifyUi(new State(pending, THRESHOLD_SEC, THRESHOLD_SEC, Phase.CREATING,
                    "Создаётся новый SPOT (задачи ждали " + THRESHOLD_SEC + "с)..."));

            // VM creation is blocking — run on a separate thread
            final AuthService        finalAuth = authService;
            final IniLoader.VmConfig finalCfg  = vmConfig;
            final String             finalUrl  = coordinatorUrl;

            new Thread(() -> {
                try {
                    new VMCreator(finalAuth).createOne(finalCfg, finalUrl);
                    phase = Phase.COOLDOWN;
                } catch (Exception e) {
                    phase = Phase.IDLE;
                    pendingSince.set(-1);
                    notifyUi(new State(0, 0, THRESHOLD_SEC, Phase.IDLE,
                            "❌ Ошибка автосоздания SPOT: " + e.getMessage()));
                }
            }, "autoscaler-create").start();

        } catch (Exception ignored) {
            // Keep scheduler alive regardless
        }
    }

    private void notifyUi(State state) {
        Consumer<State> cb = uiCallback;
        if (cb != null) {
            javafx.application.Platform.runLater(() -> cb.accept(state));
        }
    }
}
