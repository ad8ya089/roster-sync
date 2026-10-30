package com.moveinsync.rostersync;
import com.moveinsync.rostersync.domain.model.Role;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.*;
import static org.assertj.core.api.Assertions.*;

@Timeout(30) @ExtendWith(OutputCaptureExtension.class)
class PiiLeakIT extends AbstractReconciliationIntegrationTest {
    @org.springframework.beans.factory.annotation.Autowired com.moveinsync.rostersync.application.outbox.OutboxRowProcessor publisherRows;
    @Test void ingestTransportErrorsAndOutboxNeverLogOrReportCanaryValues(CapturedOutput output) throws Exception {
        var create=event("PII1",1,"CREATE","BLR-1","S1");
        create.put("name","Zzcanary Person"); create.put("email","canary.person@pii.test"); create.put("phone","+919999900000");
        send(create);
        request("PATCH","/api/v1/employees/PII1/transport-fields",jwt(Role.TRANSPORT_ADMIN),"{\"pickupPoint\":\"Canary Lane 42\",\"specialAssistance\":\"Canary Assistance\"}");
        var invalid=new LinkedHashMap<>(create); invalid.put("version",2); invalid.put("phone","+91 99999-00000");
        String error=mapper.writeValueAsString(send(invalid));
        var conflict=new LinkedHashMap<>(create); conflict.put("shiftCode","S2"); error+=mapper.writeValueAsString(send(conflict));
        String payloads=jdbc.queryForObject("SELECT json_agg(payload)::text FROM outbox_event",String.class);
        for (String canary : List.of("Zzcanary Person","canary.person@pii.test","+919999900000","+91 99999-00000","Canary Lane 42","Canary Assistance")) {
            assertThat(output.getAll()).doesNotContain(canary); assertThat(error).doesNotContain(canary); assertThat(payloads).doesNotContain(canary);
        }
    }
    @Test void reconAuthConflictAndForcedFailureKeepEveryOperationalStorePiiFree(CapturedOutput output) throws Exception {
        var canaries=List.of("ZzAllFlows Person","allflows.canary@pii.test","+919777700000","+91 97777-00000","Canary Private Gate","Canary Medical Detail","ZzSecretCanary");
        var create=event("PII_ALL",1,"CREATE","BLR-1","S1");create.put("name",canaries.get(0));create.put("email",canaries.get(1));create.put("phone",canaries.get(2));send(create);
        send(create);var conflict=new LinkedHashMap<>(create);conflict.put("shiftCode","S2");String bodies=mapper.writeValueAsString(send(conflict));
        var invalid=new LinkedHashMap<>(create);invalid.put("version",2);invalid.put("phone",canaries.get(3));bodies+=mapper.writeValueAsString(send(invalid));
        bodies+=request("PATCH","/api/v1/employees/PII_ALL/transport-fields",jwt(Role.TRANSPORT_ADMIN),mapper.writeValueAsString(Map.of("pickupPoint",canaries.get(4),"specialAssistance",canaries.get(5)))).statusCode();
        var auth=request("POST","/api/v1/auth/token",null,mapper.writeValueAsString(Map.of("username",canaries.get(1),"password",canaries.get(6))));problem(auth,401,"UNAUTHENTICATED");bodies+=auth.body();
        var failure=request("GET","/api/v1/test-errors/failure?input="+canaries.get(6),jwt(Role.OPERATOR),null);problem(failure,500,"INTERNAL_ERROR");bodies+=failure.body();
        String bad=header()+"BAD_FILE,1,"+canaries.get(0)+","+canaries.get(1)+","+canaries.get(3)+",,BLR-1,S1,ACTIVE\n";
        var run=finished(upload(bad,false));assertThat(run.status()).isEqualTo("AWAITING_APPROVAL");
        bodies+=request("GET","/api/v1/reconciliation/runs/"+run.runId()+"/rejections",jwt(Role.OPERATOR),null).body();
        assertThat(count("record_rejection")).isGreaterThanOrEqualTo(3);assertThat(count("ops_alert")).isPositive();
        send(event("PII_ALL",3,"DEACTIVATE",null,null));
        while(publisherRows.processNext()) { /* Exercise the real sink executor and deactivation consumer. */ }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE status='PENDING'",Long.class)).isZero();
        String operational=jdbc.queryForObject("SELECT coalesce(json_agg(payload)::text,'') FROM outbox_event",String.class)
                +jdbc.queryForObject("SELECT coalesce(json_agg(row_to_json(record_rejection))::text,'') FROM record_rejection",String.class)
                +jdbc.queryForObject("SELECT coalesce(json_agg(row_to_json(ops_alert))::text,'') FROM ops_alert",String.class);
        for(String canary:canaries){assertThat(output.getAll()).doesNotContain(canary);assertThat(bodies).doesNotContain(canary);assertThat(operational).doesNotContain(canary);}
    }
}
