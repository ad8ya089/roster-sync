package com.moveinsync.rostersync;
import com.moveinsync.rostersync.domain.model.Role;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

@Timeout(30)
class TransportFieldsIT extends AbstractIngestionIntegrationTest {
    private java.net.http.HttpResponse<String> patch(String body) throws Exception {
        return request("PATCH","/api/v1/employees/P1/transport-fields",jwt(Role.TRANSPORT_ADMIN),body);
    }
    @Test void nullClearsWhileAbsentRetainsAndNoOpWritesNothing() throws Exception {
        send(event("P1",1,"CREATE","BLR-1","S1"));
        assertThat(patch("{\"pickupPoint\":\"Pickup\",\"specialAssistance\":\"WHEELCHAIR\"}").statusCode()).isEqualTo(200);
        assertThat(patch("{\"pickupPoint\":null}").statusCode()).isEqualTo(200);
        var row=jdbc.queryForMap("SELECT pickup_point,special_assistance,transport_version,hr_version FROM employee WHERE tenant_id=? AND external_id='P1'",JwtTestTokens.ACME);
        assertThat(row.get("pickup_point")).isNull(); assertThat(row.get("special_assistance")).isEqualTo("WHEELCHAIR");
        assertThat(((Number)row.get("transport_version")).intValue()).isEqualTo(2);
        String before=employeeBytes("P1"); long outbox=count("outbox_event");
        assertThat(patch("{}").statusCode()).isEqualTo(200);
        assertThat(patch("{\"pickupPoint\":null,\"specialAssistance\":\"WHEELCHAIR\"}").statusCode()).isEqualTo(200);
        assertThat(employeeBytes("P1")).isEqualTo(before); assertThat(count("outbox_event")).isEqualTo(outbox);
        assertThat(version("P1")).isEqualTo(1);
    }
    @Test void inactiveEmployeesCanBePatchedAndPayloadNeverCarriesTransportValues() throws Exception {
        send(event("P1",1,"DEACTIVATE",null,null));
        assertThat(patch("{\"pickupPoint\":\"Canary Lane 42\"}").statusCode()).isEqualTo(200);
        String payload=jdbc.queryForObject("SELECT payload::text FROM outbox_event WHERE event_type='TransportProfileUpdated'",String.class);
        assertThat(payload).doesNotContain("Canary Lane 42");
        var json=mapper.readTree(payload);
        assertThat(json.get("values").size()).isZero();
        assertThat(json.get("source").asText()).isEqualTo("TRANSPORT_ADMIN");
        assertThat(json.get("changedFields").get(0).asText()).isEqualTo("pickupPoint");
    }
    @Test void hrKeysUnknownKeysInvalidTypesAndExcessiveLengthsAreRejected() throws Exception {
        send(event("P1",1,"CREATE","BLR-1","S1"));
        String before=employeeBytes("P1"); long outbox=count("outbox_event");
        problem(patch("{\"email\":\"canary.person@pii.test\"}"),400,"FIELD_NOT_OWNED_BY_SOURCE");
        problem(patch("{\"mystery\":\"canary.person@pii.test\"}"),400,"UNKNOWN_FIELD");
        problem(patch("{\"pickupPoint\":17}"),400,"VALIDATION_FAILED");
        problem(patch(mapper.writeValueAsString(java.util.Map.of("pickupPoint","X".repeat(301)))),400,"VALIDATION_FAILED");
        assertThat(employeeBytes("P1")).isEqualTo(before); assertThat(count("outbox_event")).isEqualTo(outbox);
    }
}
