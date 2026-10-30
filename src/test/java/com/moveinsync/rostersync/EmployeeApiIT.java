package com.moveinsync.rostersync;
import com.moveinsync.rostersync.domain.model.Role;
import com.moveinsync.rostersync.infrastructure.security.CurrentUser;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

@Timeout(30)
class EmployeeApiIT extends AbstractIngestionIntegrationTest {
    @Test void listAndReceiptsUseTheBoundedPageDtoAndVersionDescendingOrder() throws Exception {
        send(List.of(event("READ1",1,"CREATE","BLR-1","S1"),event("READ1",3,"UPDATE","BLR-2","S2"),event("READ1",2,"UPDATE","BLR-1","S3")));
        var response=request("GET","/api/v1/employees?officeCode=BLR-2&status=ACTIVE&page=0&size=1",jwt(Role.OPERATOR),null);
        assertThat(response.statusCode()).isEqualTo(200);
        var page=mapper.readTree(response.body());
        assertThat(page.size()).isEqualTo(4); assertThat(page.get("totalElements").asLong()).isEqualTo(1);
        assertThat(page.get("items").get(0).get("employeeId").asText()).isEqualTo("READ1");
        response=request("GET","/api/v1/employees/READ1/events?size=2",jwt(Role.TRANSPORT_ADMIN),null);
        assertThat(response.statusCode()).isEqualTo(200);
        var receipts=mapper.readTree(response.body());
        assertThat(receipts.get("totalElements").asLong()).isEqualTo(3);
        assertThat(receipts.get("items").get(0).get("version").asLong()).isEqualTo(3);
        assertThat(receipts.get("items").get(1).get("version").asLong()).isEqualTo(2);
        assertThat(response.body()).doesNotContain("payloadHash","payload_hash","fixture@example.test","Fixture Person");
        for (String path : List.of("/api/v1/employees?size=201","/api/v1/employees?page=-1","/api/v1/employees?size=canary")) {
            problem(request("GET",path,jwt(Role.TRANSPORT_ADMIN),null),400,"VALIDATION_FAILED");
        }
    }
    @Test void roleMatrixAndEmployeeSelfAccessApplyBeforeResourceLookup() throws Exception {
        for (Role role : Role.values()) {
            String jwt=jwt(role);
            int listStatus=role == Role.TRANSPORT_ADMIN || role == Role.OPERATOR ? 200 : 403;
            assertThat(request("GET","/api/v1/employees",jwt,null).statusCode()).isEqualTo(listStatus);
            int detailStatus=role == Role.HR_SYSTEM ? 403 : 200;
            assertThat(request("GET","/api/v1/employees/E1001",jwt,null).statusCode()).isEqualTo(detailStatus);
            assertThat(request("GET","/api/v1/me",jwt,null).statusCode()).isEqualTo(role == Role.EMPLOYEE ? 200 : 403);
            assertThat(request("GET","/api/v1/employees/E1001/events",jwt,null).statusCode()).isEqualTo(listStatus);
            var ingest=request("POST","/api/v1/hr/events",jwt,mapper.writeValueAsString(Map.of("events",List.of(event("AUTH1",1,"CREATE","BLR-1","S1")))));
            assertThat(ingest.statusCode()).isEqualTo(role == Role.HR_SYSTEM ? 200 : 403);
            assertThat(request("PATCH","/api/v1/employees/E1001/transport-fields",jwt,"{}").statusCode()).isEqualTo(role == Role.TRANSPORT_ADMIN ? 200 : 403);
        }
        problem(request("GET","/api/v1/employees/E1002",jwt(Role.EMPLOYEE),null),404,"NOT_FOUND");
        problem(request("GET","/api/v1/employees/MISSING",jwt(Role.HR_SYSTEM),null),403,"FORBIDDEN");
        problem(request("PATCH","/api/v1/employees/MISSING/transport-fields",jwt(Role.EMPLOYEE),"{}"),403,"FORBIDDEN");
    }
    @Test void crossTenantEmployeesAreInvisibleAndSameExternalIdCanExistSeparately() throws Exception {
        send(event("SHARED1",1,"CREATE","BLR-1","S1"));
        UUID globex=UUID.fromString("00000000-0000-0000-0000-000000000002");
        String globexAdmin=tokens.issue(new CurrentUser("globex.admin",globex,"globex",Role.TRANSPORT_ADMIN,null));
        problem(request("GET","/api/v1/employees/SHARED1",globexAdmin,null),404,"NOT_FOUND");
        problem(request("GET","/api/v1/employees/SHARED1/events",globexAdmin,null),404,"NOT_FOUND");
        problem(request("PATCH","/api/v1/employees/SHARED1/transport-fields",globexAdmin,"{}"),404,"NOT_FOUND");
        assertThat(ingestion.ingest(globex,List.of(event("SHARED1",1,"CREATE","HYD-1","S2"))).summary().applied()).isEqualTo(1);
        var response=request("GET","/api/v1/employees/SHARED1",globexAdmin,null);
        assertThat(response.statusCode()).isEqualTo(200); assertThat(mapper.readTree(response.body()).get("officeCode").asText()).isEqualTo("HYD-1");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM employee WHERE external_id='SHARED1'",Integer.class)).isEqualTo(2);
        assertThat(mapper.readTree(request("GET","/api/v1/employees/SHARED1",jwt(Role.TRANSPORT_ADMIN),null).body()).get("officeCode").asText()).isEqualTo("BLR-1");
    }
}
