package orhestra.coordinator.api.v1;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import orhestra.cloud.creator.VMCreator;
import orhestra.coordinator.api.Controller;
import orhestra.coordinator.service.ConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Admin endpoints for SPOT VM creation.
 *
 * POST /api/v1/admin/spots/create
 *   Optional query param: ?count=N  (default: uses vm_count from config)
 *   → Starts async VM creation; returns 202 Accepted immediately.
 *   Requires config to be loaded first via POST /api/v1/admin/config.
 *
 * GET /api/v1/admin/spots/create/status
 *   → Returns status of the last/current VM creation job.
 */
public class AdminSpotsController implements Controller {

    private static final Logger log = LoggerFactory.getLogger(AdminSpotsController.class);

    private static final String CREATE_PATH  = "/api/v1/admin/spots/create";
    private static final String STATUS_PATH  = "/api/v1/admin/spots/create/status";

    public enum JobState { IDLE, RUNNING, SUCCESS, ERROR }

    public record CreationStatus(
            JobState state,
            String   message,
            Instant  startedAt,
            Instant  finishedAt,
            int      vmCount
    ) {}

    private final ConfigService configService;
    private final AtomicReference<CreationStatus> lastStatus =
            new AtomicReference<>(new CreationStatus(JobState.IDLE, "No VM creation started yet", null, null, 0));

    public AdminSpotsController(ConfigService configService) {
        this.configService = configService;
    }

    @Override
    public boolean matches(HttpMethod method, String path) {
        return (CREATE_PATH.equals(path)  && HttpMethod.POST.equals(method)) ||
               (STATUS_PATH.equals(path)  && HttpMethod.GET.equals(method));
    }

    @Override
    public ControllerResponse handle(ChannelHandlerContext ctx, FullHttpRequest req, String path) {
        if (STATUS_PATH.equals(path)) {
            return handleStatus();
        } else {
            return handleCreate(req);
        }
    }

    // ── POST /api/v1/admin/spots/create ──────────────────────────────────────

    private ControllerResponse handleCreate(FullHttpRequest req) {
        if (!configService.isLoaded()) {
            return ControllerResponse.badRequest(
                    "Config not loaded. POST config.ini to /api/v1/admin/config first.");
        }

        CreationStatus current = lastStatus.get();
        if (current.state() == JobState.RUNNING) {
            return ControllerResponse.conflict("VM creation already in progress");
        }

        ConfigService.LoadedConfig cfg = configService.get().orElseThrow();
        if (cfg.authService() == null) {
            return ControllerResponse.error(
                    "AuthService not initialized — check oauth_token in [AUTH] section of config.ini");
        }

        // Parse optional ?count= query param
        int count = cfg.vmConfig().vmCount;
        String uri = req.uri();
        if (uri.contains("count=")) {
            try {
                String qs = uri.substring(uri.indexOf('?') + 1);
                for (String param : qs.split("&")) {
                    if (param.startsWith("count=")) {
                        count = Integer.parseInt(param.substring(6).trim());
                        break;
                    }
                }
            } catch (NumberFormatException ignored) {}
        }

        final int finalCount = count;
        final Instant startedAt = Instant.now();

        lastStatus.set(new CreationStatus(JobState.RUNNING,
                "Creating " + finalCount + " SPOT VM(s)...", startedAt, null, finalCount));

        // Run VM creation async — it's blocking (gRPC) so must be off the Netty thread
        final ConfigService.LoadedConfig finalCfg = cfg;
        new Thread(() -> {
            try {
                new VMCreator(finalCfg.authService())
                        .createMany(finalCfg.vmConfig(), null, finalCount);

                lastStatus.set(new CreationStatus(JobState.SUCCESS,
                        "Successfully created " + finalCount + " SPOT VM(s)",
                        startedAt, Instant.now(), finalCount));
                log.info("VM creation complete: {} VMs", finalCount);
            } catch (Exception e) {
                log.error("VM creation failed", e);
                lastStatus.set(new CreationStatus(JobState.ERROR,
                        "VM creation failed: " + e.getMessage(),
                        startedAt, Instant.now(), finalCount));
            }
        }, "vm-creator").start();

        return ControllerResponse.json(
                io.netty.handler.codec.http.HttpResponseStatus.ACCEPTED,
                String.format("{\"accepted\":true,\"vmCount\":%d,\"message\":\"VM creation started. Poll /api/v1/admin/spots/create/status for updates.\"}",
                        finalCount));
    }

    // ── GET /api/v1/admin/spots/create/status ────────────────────────────────

    private ControllerResponse handleStatus() {
        CreationStatus s = lastStatus.get();
        String json = String.format(
                "{\"state\":\"%s\",\"message\":\"%s\",\"vmCount\":%d,\"startedAt\":\"%s\",\"finishedAt\":\"%s\"}",
                s.state(),
                escapeJson(s.message()),
                s.vmCount(),
                s.startedAt() != null ? s.startedAt().toString() : "",
                s.finishedAt() != null ? s.finishedAt().toString() : "");
        return ControllerResponse.json(json);
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
