package orhestra.coordinator.api.internal.v1.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InternalDtoTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void heartbeatRequestDeserialization() throws Exception {
        String json = """
                {
                  "spotId": "42",
                  "cpuLoad": 45.2,
                  "runningTasks": 2,
                  "totalCores": 8
                }
                """;

        HeartbeatRequest req = mapper.readValue(json, HeartbeatRequest.class);

        assertEquals("42", req.spotId());
        assertEquals(45.2, req.cpuLoad(), 0.01);
        assertEquals(2, req.runningTasks());
        assertEquals(8, req.totalCores());

        assertDoesNotThrow(req::validate);
    }

    @Test
    void heartbeatRequestValidation() {
        HeartbeatRequest invalid = new HeartbeatRequest("", 50.0, 1, 4, 0L, 0L, 0.0, 0L, 0.0, 0L, 0L, 0);
        assertThrows(IllegalArgumentException.class, invalid::validate);

        HeartbeatRequest badCpu = new HeartbeatRequest("spot-1", 150.0, 1, 4, 0L, 0L, 0.0, 0L, 0.0, 0L, 0L, 0);
        assertThrows(IllegalArgumentException.class, badCpu::validate);
    }

    @Test
    void claimTasksRequestValidation() {
        ClaimTasksRequest valid = new ClaimTasksRequest("spot-1", 4);
        assertDoesNotThrow(valid::validate);

        ClaimTasksRequest tooMany = new ClaimTasksRequest("spot-1", 100);
        assertThrows(IllegalArgumentException.class, tooMany::validate);

        ClaimTasksRequest zero = new ClaimTasksRequest("spot-1", 0);
        assertThrows(IllegalArgumentException.class, zero::validate);
    }

    @Test
    void operationResponseSerialization() throws Exception {
        OperationResponse success = OperationResponse.success();
        String json = mapper.writeValueAsString(success);
        assertTrue(json.contains("\"ok\":true"));
        assertFalse(json.contains("error")); // null fields excluded

        OperationResponse error = OperationResponse.error("something_wrong");
        json = mapper.writeValueAsString(error);
        assertTrue(json.contains("\"ok\":false"));
        assertTrue(json.contains("\"error\":\"something_wrong\""));
    }

    @Test
    void helloResponseSerialization() throws Exception {
        HelloResponse resp = HelloResponse.create("42");
        String json = mapper.writeValueAsString(resp);

        assertTrue(json.contains("\"spotId\":\"42\""));
        assertTrue(json.contains("\"coordinatorVersion\":\"2.0.0\""));
    }
}
