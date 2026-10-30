package com.moveinsync.rostersync.infrastructure.persistence;
import com.moveinsync.rostersync.domain.command.HrCommand;
import com.moveinsync.rostersync.domain.model.IngestOutcome;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class HrEventReceiptDao {
    private final JdbcClient jdbc;
    public HrEventReceiptDao(JdbcClient jdbc) { this.jdbc = jdbc; }

    public boolean reserve(UUID tenantId, HrCommand command, String hash, String warnings, Instant now) {
        // A concurrent same-key INSERT blocks until the first tx ends: it then conflicts after commit,
        // or succeeds if that tx rolled back. The provisional receipt is invisible until commit.
        return jdbc.sql("""
                INSERT INTO hr_event_receipt(tenant_id, external_id, version, event_id, event_type,
                  payload_hash, outcome, warnings, received_at) VALUES(?,?,?,?,?,?,'PROCESSING',?,?)
                ON CONFLICT(tenant_id, external_id, version) DO NOTHING
                """).params(Arrays.asList(tenantId, command.employeeId(), command.version(), command.eventId(), command.type().name(),
                        hash, warnings, Timestamp.from(now))).update() == 1;
    }
    public AcceptedReceipt find(UUID tenantId, String employeeId, long version) {
        return jdbc.sql("SELECT payload_hash, outcome FROM hr_event_receipt WHERE tenant_id=? AND external_id=? AND version=?")
                .params(tenantId, employeeId, version).query((row, number) -> new AcceptedReceipt(row.getString(1), IngestOutcome.valueOf(row.getString(2)))).single();
    }
    public void complete(UUID tenantId, String employeeId, long version, IngestOutcome outcome) {
        if (!EnumSet.of(IngestOutcome.APPLIED, IngestOutcome.NO_CHANGE, IngestOutcome.STALE, IngestOutcome.DUPLICATE).contains(outcome)) {
            throw new IllegalArgumentException("Only accepted content can be receipted");
        }
        jdbc.sql("UPDATE hr_event_receipt SET outcome=? WHERE tenant_id=? AND external_id=? AND version=?")
                .params(outcome.name(), tenantId, employeeId, version).update();
    }
    public record AcceptedReceipt(String hash, IngestOutcome outcome) { }
}
