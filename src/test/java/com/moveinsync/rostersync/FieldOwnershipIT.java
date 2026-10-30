package com.moveinsync.rostersync;
import com.moveinsync.rostersync.domain.model.Role;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

@Timeout(30)
class FieldOwnershipIT extends AbstractIngestionIntegrationTest {
    @Test void hrNeverOverwritesTransportValuesAndOutboxContainsOnlyHrFieldNames() throws Exception {
        send(event("O1",1,"CREATE","BLR-1","S1"));
        assertThat(request("PATCH","/api/v1/employees/O1/transport-fields",jwt(Role.TRANSPORT_ADMIN),"{\"pickupPoint\":\"Private Pickup\",\"specialAssistance\":\"WHEELCHAIR\"}").statusCode()).isEqualTo(200);
        var update=event("O1",2,"UPDATE","BLR-2","S2"); update.put("pickupPoint","Ignored pickup");
        var result=send(update).results().getFirst();
        assertThat(result.warnings()).anyMatch(warning->warning.field().equals("pickupPoint") && warning.code().equals("FIELD_NOT_OWNED_BY_SOURCE"));
        assertThat(jdbc.queryForObject("SELECT pickup_point FROM employee WHERE tenant_id=? AND external_id='O1'",String.class,JwtTestTokens.ACME)).isEqualTo("Private Pickup");
        String payload=jdbc.queryForObject("SELECT payload::text FROM outbox_event WHERE event_type='EmployeeUpdated'",String.class);
        assertThat(payload).doesNotContain("Private Pickup","Ignored pickup","fixture@example.test","Fixture Person","+919800000001");
        assertThat(mapper.readTree(payload).get("changedFields").toString()).isEqualTo("[\"officeCode\",\"shiftCode\"]");
    }
}
