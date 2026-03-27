package orhestra.coordinator.api.v1;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import orhestra.coordinator.api.Controller;
import orhestra.coordinator.service.ConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;

/**
 * Admin endpoints for cloud config management.
 *
 * POST /api/v1/admin/config
 *   Body: raw content of config.ini
 *   → Parses and stores config in memory; returns 200 on success, 400 on parse error.
 *
 * GET /api/v1/admin/config
 *   → Returns current parsed config as JSON (secrets masked with "***").
 */
public class AdminConfigController implements Controller {

    private static final Logger log = LoggerFactory.getLogger(AdminConfigController.class);
    private static final String PATH = "/api/v1/admin/config";

    private final ConfigService configService;

    public AdminConfigController(ConfigService configService) {
        this.configService = configService;
    }

    @Override
    public boolean matches(HttpMethod method, String path) {
        return PATH.equals(path) &&
               (HttpMethod.POST.equals(method) || HttpMethod.GET.equals(method));
    }

    @Override
    public ControllerResponse handle(ChannelHandlerContext ctx, FullHttpRequest req, String path) {
        if (HttpMethod.GET.equals(req.method())) {
            return handleGet();
        } else {
            return handlePost(req);
        }
    }

    private ControllerResponse handleGet() {
        return ControllerResponse.json(configService.toSafeJson());
    }

    private ControllerResponse handlePost(FullHttpRequest req) {
        String body = req.content().toString(StandardCharsets.UTF_8).trim();
        if (body.isEmpty()) {
            return ControllerResponse.badRequest("Request body must contain config.ini content");
        }
        try {
            configService.load(body);
            log.info("Config loaded via API");
            return ControllerResponse.json("{\"success\":true,\"message\":\"Config loaded successfully\"}");
        } catch (IllegalArgumentException e) {
            log.warn("Config parse error: {}", e.getMessage());
            return ControllerResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            log.error("Config load error", e);
            return ControllerResponse.error("Failed to load config: " + e.getMessage());
        }
    }
}
