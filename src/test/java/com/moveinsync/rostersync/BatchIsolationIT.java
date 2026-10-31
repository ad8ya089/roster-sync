package com.moveinsync.rostersync;
import com.moveinsync.rostersync.domain.model.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

@Timeout(30)
@org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
class BatchIsolationIT extends AbstractIngestionIntegrationTest {
    @Test void databasePoisonInTheMiddleDoesNotStopLaterRecords(org.springframework.boot.test.system.CapturedOutput output) throws Exception {
        jdbc.execute("ALTER TABLE employee ADD CONSTRAINT hardening_poison CHECK (external_id <> 'DB3')");
        try {
            var batch=new ArrayList<Map<String,Object>>();
            for(int n=1;n<=5;n++) batch.add(event("DB"+n,1,"CREATE","BLR-1","S1"));
            batch.get(2).put("name","CANARY_POISON_NAME");
            var result=send(batch);
            assertThat(result.results()).extracting(row->row.outcome().name()).containsExactly("APPLIED","APPLIED","REJECTED","APPLIED","APPLIED");
            assertThat(result.results().get(2).errors()).extracting(error->error.code()).containsExactly("UNPROCESSABLE_RECORD");
            assertThat(jdbc.queryForObject("SELECT error_codes FROM record_rejection",String.class)).isEqualTo("record:UNPROCESSABLE_RECORD");
            assertThat(count("hr_event_receipt")).isEqualTo(4);
            assertThat(output.getAll()).doesNotContain("CANARY_POISON_NAME","hardening_poison");
        } finally {jdbc.execute("ALTER TABLE employee DROP CONSTRAINT hardening_poison");}
    }
    @Test void connectionFailureStopsAtRecordThreeAndKeepsEarlierCommits(org.springframework.boot.test.system.CapturedOutput output) throws Exception {
        jdbc.execute("CREATE FUNCTION hardening_connection_loss() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.external_id='LOSS3' THEN RAISE EXCEPTION USING ERRCODE='08006', MESSAGE='CANARY_CONNECTION_VALUE'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER hardening_connection BEFORE INSERT ON employee FOR EACH ROW EXECUTE FUNCTION hardening_connection_loss()");
        try {
            var batch=new ArrayList<Map<String,Object>>();for(int n=1;n<=5;n++)batch.add(event("LOSS"+n,1,"CREATE","BLR-1","S1"));
            var response=request("POST","/api/v1/hr/events",jwt(Role.HR_SYSTEM),mapper.writeValueAsString(Map.of("events",batch)));
            problem(response,503,"DEPENDENCY_UNAVAILABLE");assertThat(response.body()).doesNotContain("CANARY_CONNECTION_VALUE");
            assertThat(version("LOSS1")).isEqualTo(1);assertThat(version("LOSS2")).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM employee WHERE external_id IN ('LOSS3','LOSS4','LOSS5')",Long.class)).isZero();
            assertThat(count("hr_event_receipt")).isEqualTo(2);assertThat(employeeOutbox()).isEqualTo(2);assertThat(count("record_rejection")).isZero();
            assertThat(output.getAll()).doesNotContain("CANARY_CONNECTION_VALUE");
        } finally {jdbc.execute("DROP TRIGGER hardening_connection ON employee");jdbc.execute("DROP FUNCTION hardening_connection_loss()");}
    }
    @Test void nulNameIsRejectedWithoutStoppingTheBatch() throws Exception {
        var batch=new ArrayList<Map<String,Object>>();
        for(int n=1;n<=5;n++) batch.add(event("NUL"+n,1,"CREATE","BLR-1","S1"));
        batch.get(2).put("name","Fixture\u0000Person");
        var result=send(batch);
        assertThat(result.results()).extracting(row->row.outcome().name()).containsExactly("APPLIED","APPLIED","REJECTED","APPLIED","APPLIED");
        assertThat(result.results().get(2).errors()).extracting(error->error.code()).contains("INVALID_FORMAT");
    }
    @Test void invalidRowsAreIsolatedAndTheirKeysRemainUnconsumed() throws Exception {
        var events=mapper.convertValue(mapper.readTree(Files.readString(Path.of("samples/bad-batch.json"))).get("events"),List.class);
        var response=send(events);
        assertThat(response.summary().applied()).isEqualTo(3); assertThat(response.summary().rejected()).isEqualTo(2);
        assertThat(count("hr_event_receipt")).isEqualTo(3); assertThat(count("record_rejection")).isEqualTo(2); assertThat(employeeOutbox()).isEqualTo(3);
        assertThat(response.results().get(3).errors()).extracting(error->error.code()).contains("INVALID_FORMAT");
        assertThat(response.results().get(4).errors()).extracting(error->error.code()).contains("UNKNOWN_OFFICE");
        assertThat(send(event("B0004",1,"CREATE","BLR-1","S1")).summary().applied()).isEqualTo(1);
        assertThat(count("hr_event_receipt")).isEqualTo(4);
    }
    @Test void unknownPropertiesWarnAndDeactivateIgnoresCarriedHrFieldsOfAnyJsonType() throws Exception {
        var first=event("B1",1,"CREATE","BLR-1","S1"); first.put("unrecognised",Map.of("secret","canary.person@pii.test"));
        assertThat(send(first).results().getFirst().warnings()).anyMatch(warning->warning.code().equals("UNKNOWN_FIELD_IGNORED"));
        var deactivate=event("B1",2,"DEACTIVATE",null,null); deactivate.put("phone",List.of("not a phone")); deactivate.put("name",7);
        var result=send(deactivate).results().getFirst();
        assertThat(result.outcome()).isEqualTo(IngestOutcome.APPLIED);
        assertThat(result.warnings()).hasSize(2).allMatch(warning->warning.code().equals("UNKNOWN_FIELD_IGNORED"));
    }
    @Test void malformedRecordTypesDoNotAbortOtherRecordsAndErrorsHaveNoValues() throws Exception {
        var bad=event("B2",1,"CREATE","BLR-1","S1"); bad.put("phone","+91 9800000001"); bad.put("email",7); bad.put("name",List.of("canary"));
        var result=send(List.of(bad,"invalid-record",event("B3",1,"CREATE","BLR-1","S1")));
        assertThat(result.summary().rejected()).isEqualTo(2); assertThat(result.summary().applied()).isEqualTo(1);
        assertThat(result.results().getFirst().errors()).extracting(error->error.field()).isSorted();
        assertThat(mapper.writeValueAsString(result)).doesNotContain("canary","+91 9800000001","invalid-record");
    }
    @Test void envelopeLimitsAreEnforced() throws Exception {
        problem(request("POST","/api/v1/hr/events",jwt(Role.HR_SYSTEM),"{\"events\":[]}"),400,"VALIDATION_FAILED");
        problem(request("POST","/api/v1/hr/events",jwt(Role.HR_SYSTEM),mapper.writeValueAsString(Map.of("events",Collections.nCopies(1001,event("B4",1,"DEACTIVATE",null,null))))),400,"BATCH_TOO_LARGE");
    }
    @org.junit.jupiter.params.ParameterizedTest(name="chunked={0}")
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void declaredAndChunkedBodyLimitsAreEnforced(boolean chunked) throws Exception {
        byte[] large="x".repeat(com.moveinsync.rostersync.api.hr.EventBodyLimitFilter.MAX_BYTES+1).getBytes(StandardCharsets.UTF_8);
        var client=HttpClient.newHttpClient();
            var publisher=chunked ? HttpRequest.BodyPublishers.ofInputStream(()->new java.io.ByteArrayInputStream(large)) : HttpRequest.BodyPublishers.ofByteArray(large);
            var request=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1/hr/events")).timeout(java.time.Duration.ofSeconds(10))
                    .header("Authorization","Bearer "+jwt(Role.HR_SYSTEM)).header("Content-Type","application/json").POST(publisher).build();
            problem(client.send(request,HttpResponse.BodyHandlers.ofString()),413,"PAYLOAD_TOO_LARGE");
    }
}
