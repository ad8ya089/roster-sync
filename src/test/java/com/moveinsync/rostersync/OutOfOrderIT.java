package com.moveinsync.rostersync;
import com.moveinsync.rostersync.domain.model.IngestOutcome;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

@Timeout(30)
class OutOfOrderIT extends AbstractIngestionIntegrationTest {
    @Test void outboxUsesTheExactPublicPayloadAndNewHrVersion() throws Exception {
        send(event("PAYLOAD1",42,"CREATE","BLR-1","S1"));
        var row=jdbc.queryForMap("SELECT aggregate_type,aggregate_version,payload::text AS payload FROM outbox_event");
        assertThat(row.get("aggregate_type")).isEqualTo("Employee");
        assertThat(((Number)row.get("aggregate_version")).longValue()).isEqualTo(42);
        String encoded=(String)row.get("payload"); var payload=mapper.readTree(encoded);
        assertThat(payload.size()).isEqualTo(9);
        for(String key:List.of("eventId","eventType","tenantId","employeeId","version","occurredAt","source","changedFields","values")) assertThat(payload.has(key)).isTrue();
        assertThat(payload.get("version").asLong()).isEqualTo(42);
        assertThat(payload.get("source").asText()).isEqualTo("API");
        assertThat(java.time.Instant.parse(payload.get("occurredAt").asText())).isNotNull();
        assertThat(payload.get("values").size()).isEqualTo(4);
        assertThat(encoded).doesNotContain("Fixture Person","fixture@example.test","+919800000001");
    }
    @Test void briefExample() throws Exception {
        var result=send(List.of(event("E2001",5,"CREATE","BLR-1","S1"),event("E2001",7,"UPDATE","BLR-2","S1"),event("E2001",6,"UPDATE","BLR-1","S3")));
        assertThat(result.results()).extracting(row -> row.outcome()).containsExactly(IngestOutcome.APPLIED,IngestOutcome.APPLIED,IngestOutcome.STALE);
        assertThat(result.results().getLast().currentVersion()).isEqualTo(7);
        assertThat(version("E2001")).isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT office.code||':'||employee.shift_code FROM employee JOIN office ON employee.office_id=office.id WHERE employee.tenant_id=? AND employee.external_id='E2001'",String.class,JwtTestTokens.ACME)).isEqualTo("BLR-2:S1");
        assertThat(employeeOutbox()).isEqualTo(2); assertThat(count("hr_event_receipt")).isEqualTo(3);
    }
    @Test void identicalHigherVersionAdvancesTimestampWithoutAnEvent() throws Exception {
        send(event("E2002",7,"CREATE","BLR-1","S1"));
        jdbc.update("UPDATE employee SET hr_updated_at='2020-01-01T00:00:00Z' WHERE tenant_id=? AND external_id='E2002'",JwtTestTokens.ACME);
        var result=send(event("E2002",8,"UPDATE","BLR-1","S1"));
        assertThat(result.summary().noChange()).isEqualTo(1);
        assertThat(version("E2002")).isEqualTo(8);
        assertThat(jdbc.queryForObject("SELECT hr_updated_at > '2020-01-01T00:00:00Z' FROM employee WHERE tenant_id=? AND external_id='E2002'",Boolean.class,JwtTestTokens.ACME)).isTrue();
        assertThat(employeeOutbox()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT outcome FROM hr_event_receipt WHERE tenant_id=? AND external_id='E2002' AND version=8",String.class,JwtTestTokens.ACME)).isEqualTo("NO_CHANGE");
        assertThat(send(event("E2002",8,"UPDATE","BLR-1","S1")).results().getFirst().originalOutcome()).isEqualTo(IngestOutcome.NO_CHANGE);
    }
}
