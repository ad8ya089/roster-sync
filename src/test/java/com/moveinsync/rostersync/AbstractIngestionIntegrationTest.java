package com.moveinsync.rostersync;
import com.moveinsync.rostersync.application.ingest.*;
import com.moveinsync.rostersync.domain.model.Role;
import com.moveinsync.rostersync.infrastructure.security.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;

public abstract class AbstractIngestionIntegrationTest extends AbstractSecurityIntegrationTest {
    @Autowired protected EventIngestionService ingestion;
    @Autowired protected JwtTokenService tokens;
    protected static Map<String,Object> event(String id, long version, String type, String office, String shift) {
        var event=new LinkedHashMap<String,Object>();
        event.put("employeeId",id); event.put("version",version); event.put("type",type);
        if (!type.equals("DEACTIVATE")) {
            event.put("name","Fixture Person"); event.put("email","fixture@example.test"); event.put("phone","+919800000001");
            event.put("officeCode",office); event.put("shiftCode",shift); event.put("status","ACTIVE");
        }
        return event;
    }
    protected String jwt(Role role) { return tokens.issue(new CurrentUser("fixture",JwtTestTokens.ACME,"acme",role,role == Role.EMPLOYEE ? "E1001" : null)); }
    protected IngestResult send(List<?> events) throws Exception {
        var response=request("POST","/api/v1/hr/events",jwt(Role.HR_SYSTEM),mapper.writeValueAsString(Map.of("events",events)));
        org.assertj.core.api.Assertions.assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return mapper.readValue(response.body(),IngestResult.class);
    }
    protected IngestResult send(Map<String,Object> event) throws Exception { return send(List.of(event)); }
    protected long count(String table) { return jdbc.queryForObject("SELECT count(*) FROM "+table,Long.class); }
    protected long employeeOutbox() { return jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE event_type LIKE 'Employee%'",Long.class); }
    protected long version(String id) { return jdbc.queryForObject("SELECT hr_version FROM employee WHERE tenant_id=? AND external_id=?",Long.class,JwtTestTokens.ACME,id); }
    protected String employeeBytes(String id) { return jdbc.queryForObject("SELECT row_to_json(employee)::text FROM employee WHERE tenant_id=? AND external_id=?",String.class,JwtTestTokens.ACME,id); }
}
