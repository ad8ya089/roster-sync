package com.moveinsync.rostersync;
import com.moveinsync.rostersync.domain.model.IngestOutcome;
import java.util.List;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

@Timeout(30)
class TombstoneIT extends AbstractIngestionIntegrationTest {
    @Test void noChangeOnTombstonePreservesTheHighWatermarkAndPreventsResurrection() throws Exception {
        var result=send(List.of(event("T1",5,"DEACTIVATE",null,null),event("T1",8,"DEACTIVATE",null,null),event("T1",6,"CREATE","BLR-1","S1"),event("T1",9,"CREATE","BLR-1","S1")));
        assertThat(result.results()).extracting(row->row.outcome()).containsExactly(IngestOutcome.APPLIED,IngestOutcome.NO_CHANGE,IngestOutcome.STALE,IngestOutcome.APPLIED);
        assertThat(result.results().get(2).currentVersion()).isEqualTo(8);
        assertThat(version("T1")).isEqualTo(9);
        assertThat(jdbc.queryForList("SELECT event_type FROM outbox_event ORDER BY id",String.class)).containsExactly("EmployeeCreated","EmployeeCreated");
    }
    @Test void inactiveFullRecordDeactivationAndIdenticalUpdateAdvanceWithoutOutbox() throws Exception {
        var inactive=event("T2",5,"CREATE","BLR-1","S1"); inactive.put("status","INACTIVE"); send(inactive);
        assertThat(send(event("T2",7,"DEACTIVATE",null,null)).summary().noChange()).isEqualTo(1);
        var update=event("T2",8,"UPDATE","BLR-1","S1"); update.put("status","INACTIVE");
        assertThat(send(update).summary().noChange()).isEqualTo(1);
        assertThat(version("T2")).isEqualTo(8); assertThat(employeeOutbox()).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT outcome FROM hr_event_receipt ORDER BY version",String.class)).containsExactly("APPLIED","NO_CHANGE","NO_CHANGE");
    }
}
