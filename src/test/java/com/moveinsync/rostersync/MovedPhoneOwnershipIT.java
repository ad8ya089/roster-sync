package com.moveinsync.rostersync;
import com.moveinsync.rostersync.domain.model.Role;
import org.junit.jupiter.api.*;
import org.springframework.test.context.TestPropertySource;
import static org.assertj.core.api.Assertions.*;

@Timeout(30) @TestPropertySource(properties="roster.ownership.transport-owned=PHONE,PICKUP_POINT,SPECIAL_ASSISTANCE")
class MovedPhoneOwnershipIT extends AbstractIngestionIntegrationTest {
    @Test void movingPhoneOwnershipChangesBothWritePaths() throws Exception {
        var first=send(event("PHONE1",1,"CREATE","BLR-1","S1"));
        assertThat(first.results().getFirst().warnings()).anyMatch(warning->warning.field().equals("phone"));
        assertThat(request("PATCH","/api/v1/employees/PHONE1/transport-fields",jwt(Role.TRANSPORT_ADMIN),"{\"phone\":\"+919800000002\"}").statusCode()).isEqualTo(200);
        var next=event("PHONE1",2,"UPDATE","BLR-1","S1"); next.put("phone","+919800000003");
        assertThat(send(next).summary().noChange()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT phone FROM employee WHERE tenant_id=? AND external_id='PHONE1'",String.class,JwtTestTokens.ACME)).isEqualTo("+919800000002");
        assertThat(employeeOutbox()).isEqualTo(1);
        problem(request("PATCH","/api/v1/employees/PHONE1/transport-fields",jwt(Role.TRANSPORT_ADMIN),"{\"phone\":\"+91 9800000002\"}"),400,"VALIDATION_FAILED");
    }
}
