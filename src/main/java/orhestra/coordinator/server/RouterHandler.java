package orhestra.coordinator.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.*;
import orhestra.coordinator.api.Controller;
import orhestra.coordinator.api.Controller.ControllerResponse;
import orhestra.coordinator.config.CoordinatorConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE;
import static io.netty.handler.codec.http.HttpResponseStatus.*;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

/**
 * Central router that dispatches HTTP requests to registered controllers.
 * 
 * Only handles versioned API endpoints:
 * - /api/v1/* (public API)
 * - /internal/v1/* (internal agent API)
 * 
 * All other endpoints return 404.
 * 
 * This handler is @Sharable because it has no per-channel state.
 */
@Sharable
public class RouterHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private static final Logger log = LoggerFactory.getLogger(RouterHandler.class);

    /**
     * Shared JSON ObjectMapper configured for:
     * - Java 8 date/time types (Instant, LocalDate, etc.) as ISO strings
     * - NOT as numeric timestamps
     */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);

    private final List<Controller> controllers = new ArrayList<>();
    private final CoordinatorConfig config;

    public RouterHandler(CoordinatorConfig config) {
        this.config = config;
    }

    /**
     * Register a controller to handle requests.
     * Controllers are checked in order of registration.
     */
    public RouterHandler registerController(Controller controller) {
        controllers.add(controller);
        log.debug("Registered controller: {}", controller.getClass().getSimpleName());
        return this;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) {
        String uri = req.uri();
        HttpMethod method = req.method();

        // Extract path without query string
        String path = uri.contains("?") ? uri.substring(0, uri.indexOf("?")) : uri;

        try {
            // Check auth for internal endpoints
            if (!checkAuth(req, path)) {
                log.warn("Auth failed for {} {}", method, path);
                writeSafe(ctx, FORBIDDEN, "application/json", "{\"error\":\"forbidden\"}");
                return;
            }

            // Try registered controllers
            for (Controller controller : controllers) {
                if (controller.matches(method, path)) {
                    ControllerResponse response = controller.handle(ctx, req, path);
                    writeSafe(ctx, response.status(), response.contentType(), response.body());
                    return;
                }
            }

            // No controller matched - return 404
            log.debug("No handler for: {} {}", method, path);
            writeSafe(ctx, NOT_FOUND, "application/json", "{\"error\":\"not found\"}");

        } catch (IllegalArgumentException e) {
            // Validation errors
            log.warn("Validation error: {}", e.getMessage());
            writeSafe(ctx, BAD_REQUEST, "application/json",
                    "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
        } catch (Throwable t) {
            // Catch ALL exceptions including Error, OutOfMemoryError, etc.
            // Log full details including request body for debugging
            String requestBody = "";
            try {
                requestBody = req.content().toString(StandardCharsets.UTF_8);
            } catch (Exception ignored) {
            }

            log.error("Handler error: {} {} - Body: [{}] - Exception: {}", method, path, requestBody, t.toString(), t);
            System.err.println("=== ROUTER HANDLER EXCEPTION ===");
            System.err.println("Method: " + method + " Path: " + path);
            System.err.println("Request Body: " + requestBody);
            t.printStackTrace(System.err);
            System.err.println("=================================");

            // Build full error chain for debugging
            StringBuilder errorChain = new StringBuilder(t.toString());
            Throwable cause = t.getCause();
            while (cause != null) {
                errorChain.append(" <- ").append(cause.toString());
                cause = cause.getCause();
            }

            writeSafe(ctx, INTERNAL_SERVER_ERROR, "application/json",
                    "{\"error\":\"" + escapeJson(errorChain.toString()) + "\"}");
        }
    }

    /**
     * Check if request requires and passes auth.
     *
     * /internal/*        → requires X-Orhestra-Key     (ORHESTRA_AGENT_KEY, used by SPOT agents)
     * /api/v1/admin/*    → requires X-Orhestra-Admin-Key (ORHESTRA_ADMIN_KEY, used by plugin/admin)
     * everything else    → public, no auth required
     */
    private boolean checkAuth(FullHttpRequest req, String path) {
        if (path.startsWith("/internal/")) {
            if (!config.hasAgentKey()) return true;
            String key = req.headers().get("X-Orhestra-Key");
            return config.agentKey().equals(key);
        }

        if (path.startsWith("/api/v1/admin/")) {
            if (!config.hasAdminKey()) return true;
            String key = req.headers().get("X-Orhestra-Admin-Key");
            return config.adminKey().equals(key);
        }

        return true;
    }

    /**
     * Safe write that catches any exceptions during response writing.
     * Ensures we never silently close the connection.
     */
    private void writeSafe(ChannelHandlerContext ctx, HttpResponseStatus status, String contentType, String body) {
        try {
            if (body == null) {
                body = "";
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            FullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, status, Unpooled.wrappedBuffer(bytes));
            response.headers().set(CONTENT_TYPE, contentType + "; charset=utf-8");
            response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, bytes.length);
            ctx.writeAndFlush(response);
        } catch (Throwable t) {
            // Last resort - log and try to send simple error
            log.error("Failed to write response: {}", t.getMessage(), t);
            System.err.println("=== FAILED TO WRITE RESPONSE ===");
            t.printStackTrace(System.err);
            try {
                byte[] errorBytes = "{\"error\":\"failed to write response\"}".getBytes(StandardCharsets.UTF_8);
                FullHttpResponse errorResponse = new DefaultFullHttpResponse(HTTP_1_1, INTERNAL_SERVER_ERROR,
                        Unpooled.wrappedBuffer(errorBytes));
                errorResponse.headers().set(CONTENT_TYPE, "application/json; charset=utf-8");
                errorResponse.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, errorBytes.length);
                ctx.writeAndFlush(errorResponse);
            } catch (Throwable t2) {
                log.error("Complete failure writing error response", t2);
                ctx.close();
            }
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Unhandled exception in channel: {}", cause.getMessage(), cause);
        System.err.println("=== CHANNEL EXCEPTION ===");
        cause.printStackTrace(System.err);
        try {
            writeSafe(ctx, INTERNAL_SERVER_ERROR, "application/json",
                    "{\"error\":\"channel error: " + escapeJson(cause.getMessage()) + "\"}");
        } finally {
            ctx.close();
        }
    }

    private static String escapeJson(String s) {
        if (s == null)
            return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    /**
     * Get the shared ObjectMapper for JSON serialization.
     */
    public static ObjectMapper mapper() {
        return MAPPER;
    }
}
