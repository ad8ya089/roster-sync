package com.moveinsync.rostersync;
import com.moveinsync.rostersync.domain.model.Role;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.*;
import static org.assertj.core.api.Assertions.*;

@Timeout(30) @ExtendWith(OutputCaptureExtension.class)
class PiiLeakIT extends AbstractIngestionIntegrationTest {
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
}
