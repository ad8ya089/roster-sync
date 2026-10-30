package com.moveinsync.rostersync;
import com.moveinsync.rostersync.domain.model.IngestOutcome;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

@Timeout(30)
class ReplayIdempotencyIT extends AbstractIngestionIntegrationTest {
    @Test void replayTheSameFeedTwiceChangesNothing() throws Exception {
        var batch=mapper.readTree(Files.readString(Path.of("samples/feed.json")));
        var events=mapper.convertValue(batch.get("events"),List.class);
        var first=send(events);
        assertThat(first.summary().applied()).isEqualTo(44);
        assertThat(first.summary().stale()).isEqualTo(3);
        assertThat(first.summary().duplicate()).isEqualTo(3);
        String before=jdbc.queryForObject("SELECT json_agg(row_to_json(employee) ORDER BY external_id)::text FROM employee WHERE tenant_id=?",String.class,JwtTestTokens.ACME);
        String receipts=jdbc.queryForObject("SELECT json_agg(row_to_json(hr_event_receipt) ORDER BY id)::text FROM hr_event_receipt",String.class);
        long outbox=employeeOutbox(); long receiptCount=count("hr_event_receipt");
        var second=send(events);
        assertThat(second.summary().duplicate()).isEqualTo(50);
        assertThat(second.results()).allSatisfy(result->assertThat(result.originalOutcome()).isIn(IngestOutcome.APPLIED,IngestOutcome.STALE));
        assertThat(employeeOutbox()).isEqualTo(outbox);
        assertThat(count("hr_event_receipt")).isEqualTo(receiptCount);
        assertThat(jdbc.queryForObject("SELECT json_agg(row_to_json(employee) ORDER BY external_id)::text FROM employee WHERE tenant_id=?",String.class,JwtTestTokens.ACME)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT json_agg(row_to_json(hr_event_receipt) ORDER BY id)::text FROM hr_event_receipt",String.class)).isEqualTo(receipts);
    }
    @Test void informationalMetadataChangesStillReplayAndSameKeyTwiceInBatchIsDuplicate() throws Exception {
        var first=event("R1",1,"CREATE","BLR-1","S1"); first.put("eventId","first"); first.put("occurredAt","2026-01-01T00:00:00Z");
        var second=new LinkedHashMap<>(first); second.put("eventId","second"); second.put("occurredAt","2026-02-01T00:00:00Z"); second.put("email"," FIXTURE@EXAMPLE.TEST ");
        var result=send(List.of(first,second));
        assertThat(result.results()).extracting(row->row.outcome()).containsExactly(IngestOutcome.APPLIED,IngestOutcome.DUPLICATE);
        assertThat(result.results().getLast().originalOutcome()).isEqualTo(IngestOutcome.APPLIED);
        assertThat(count("hr_event_receipt")).isEqualTo(1); assertThat(employeeOutbox()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT email FROM employee WHERE tenant_id=? AND external_id='R1'",String.class,JwtTestTokens.ACME)).isEqualTo("fixture@example.test");
    }
    @Test void differentTypeOrContentConflictsWithoutChangingExistingAcceptedReceipt() throws Exception {
        var original=event("R2",7,"CREATE","BLR-1","S1"); send(original);
        String row=employeeBytes("R2"); String hash=jdbc.queryForObject("SELECT payload_hash FROM hr_event_receipt",String.class);
        assertThat(send(event("R2",7,"UPDATE","BLR-1","S1")).summary().conflict()).isEqualTo(1);
        var changed=new LinkedHashMap<>(original); changed.put("shiftCode","S2");
        assertThat(send(changed).summary().conflict()).isEqualTo(1);
        assertThat(count("hr_event_receipt")).isEqualTo(1); assertThat(employeeOutbox()).isEqualTo(1);
        assertThat(count("record_rejection")).isEqualTo(2);
        assertThat(employeeBytes("R2")).isEqualTo(row);
        assertThat(jdbc.queryForObject("SELECT payload_hash FROM hr_event_receipt",String.class)).isEqualTo(hash);
    }
    @Test void seededOrReconciledSameVersionConflictCannotPoisonACorrectResend() throws Exception {
        var contentA=event("R3",7,"UPDATE","BLR-1","S1"); send(contentA);
        jdbc.update("DELETE FROM hr_event_receipt WHERE tenant_id=? AND external_id='R3'",JwtTestTokens.ACME);
        String before=employeeBytes("R3");
        var contentB=event("R3",7,"UPDATE","BLR-1","S2");
        var conflict=send(contentB).results().getFirst();
        assertThat(conflict.outcome()).isEqualTo(IngestOutcome.CONFLICT); assertThat(conflict.currentVersion()).isEqualTo(7);
        assertThat(count("hr_event_receipt")).isZero(); assertThat(count("record_rejection")).isEqualTo(1);
        var correct=send(contentA).results().getFirst();
        assertThat(correct.outcome()).isEqualTo(IngestOutcome.DUPLICATE); assertThat(correct.originalOutcome()).isEqualTo(IngestOutcome.DUPLICATE);
        assertThat(send(contentB).summary().conflict()).isEqualTo(1);
        assertThat(count("record_rejection")).isEqualTo(2); assertThat(count("hr_event_receipt")).isEqualTo(1);
        assertThat(employeeBytes("R3")).isEqualTo(before); assertThat(employeeOutbox()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT outcome FROM hr_event_receipt",String.class)).isEqualTo("DUPLICATE");
    }
    @Test void staleReceiptReplaysWithItsOriginalOutcome() throws Exception {
        send(event("R4",9,"CREATE","BLR-1","S1"));
        var stale=event("R4",7,"UPDATE","BLR-1","S2");
        assertThat(send(stale).summary().stale()).isEqualTo(1);
        assertThat(send(stale).results().getFirst().originalOutcome()).isEqualTo(IngestOutcome.STALE);
    }
}
