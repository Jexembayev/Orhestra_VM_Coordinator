package orhestra.coordinator.api.internal.v1;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import orhestra.coordinator.api.Controller;
import orhestra.coordinator.api.internal.v1.dto.HeartbeatRequest;
import orhestra.coordinator.api.internal.v1.dto.HelloRequest;
import orhestra.coordinator.api.internal.v1.dto.HelloResponse;
import orhestra.coordinator.api.internal.v1.dto.OperationResponse;
import orhestra.coordinator.core.AppBus;
import orhestra.coordinator.server.RouterHandler;
import orhestra.coordinator.service.SpotService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * Controller for SPOT heartbeat and registration (internal API).
 * POST /internal/v1/hello - Register new SPOT
 * POST /internal/v1/heartbeat - SPOT heartbeat
 */
public class HeartbeatController implements Controller {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatController.class);

    private final SpotService spotService;

    public HeartbeatController(SpotService spotService) {
        this.spotService = spotService;
    }

    @Override
    public boolean matches(HttpMethod method, String path) {
        if (!method.equals(HttpMethod.POST)) {
            return false;
        }
        return "/internal/v1/hello".equals(path) || "/internal/v1/heartbeat".equals(path);
    }

    @Override
    public ControllerResponse handle(ChannelHandlerContext ctx, FullHttpRequest req, String path) {
        try {
            if (path.equals("/internal/v1/hello")) {
                return handleHello(ctx, req);
            } else {
                return handleHeartbeat(ctx, req);
            }
        } catch (IllegalArgumentException e) {
            return ControllerResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            log.error("Heartbeat controller error: {}", e.toString(), e);
            // Return full error for debugging
            String error = e.toString();
            if (e.getCause() != null) {
                error += " <- " + e.getCause().toString();
            }
            return ControllerResponse.error(error);
        }
    }

    /**
     * POST /internal/v1/hello - Register new SPOT node with capabilities
     */
    private ControllerResponse handleHello(ChannelHandlerContext ctx, FullHttpRequest req) throws Exception {
        String clientIp = ((InetSocketAddress) ctx.channel().remoteAddress())
                .getAddress().getHostAddress();

        String body = req.content().toString(StandardCharsets.UTF_8);

        String spotId;
        if (body != null && !body.isBlank()) {
            HelloRequest helloReq = RouterHandler.mapper().readValue(body, HelloRequest.class);
            String capJson = helloReq.capabilitiesJson(RouterHandler.mapper());
            String labels = helloReq.labelsString();
            HelloRequest.SpotInfo si = helloReq.spotInfo();
            int maxConcurrent = si != null ? si.maxConcurrent() : 0;
            int cores         = si != null ? si.cpuCores()      : 0;
            long ramMb        = si != null ? si.ramMb()         : 0;
            String hostname      = si != null ? si.hostname()      : null;
            String agentVersion  = si != null ? si.agentVersion()  : null;
            String osName        = si != null ? si.osName()        : null;
            String jvmVersion    = si != null ? si.jvmVersion()    : null;
            double totalDiskGb   = si != null ? si.totalDiskGb()   : 0;
            spotId = spotService.registerSpot(clientIp, cores, ramMb, maxConcurrent, capJson, labels,
                    hostname, agentVersion, osName, jvmVersion, totalDiskGb);
        } else {
            // Legacy: no body
            spotId = spotService.registerSpot(clientIp);
        }

        HelloResponse response = HelloResponse.create(spotId);
        return ControllerResponse.json(RouterHandler.mapper().writeValueAsString(response));
    }

    /**
     * POST /internal/v1/heartbeat - Process heartbeat from SPOT
     */
    private ControllerResponse handleHeartbeat(ChannelHandlerContext ctx, FullHttpRequest req) throws Exception {
        String body = req.content().toString(StandardCharsets.UTF_8);
        HeartbeatRequest request = RouterHandler.mapper().readValue(body, HeartbeatRequest.class);

        // Validate
        request.validate();

        String clientIp = ((InetSocketAddress) ctx.channel().remoteAddress())
                .getAddress().getHostAddress();

        // Process heartbeat
        spotService.heartbeat(
                request.spotId(),
                clientIp,
                request.cpuLoad(),
                request.runningTasks(),
                request.totalCores(),
                request.ramUsedMb(),
                request.ramTotalMb(),
                request.loadAvg1m(),
                request.swapUsedMb(),
                request.diskFreeGb(),
                request.jvmHeapUsedMb(),
                request.jvmHeapMaxMb(),
                request.cachedArtifacts());

        // Fire UI event
        AppBus.fireSpotsChanged();

        OperationResponse response = OperationResponse.success();
        return ControllerResponse.json(RouterHandler.mapper().writeValueAsString(response));
    }
}
