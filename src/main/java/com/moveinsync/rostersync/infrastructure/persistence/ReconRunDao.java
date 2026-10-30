package com.moveinsync.rostersync.infrastructure.persistence;
import com.moveinsync.rostersync.api.error.*;
import com.moveinsync.rostersync.application.outbox.OutboxWriter;
import com.moveinsync.rostersync.domain.recon.*;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ReconRunDao {
    private final JdbcClient jdbc;private final Clock clock;private final OutboxWriter outbox;
    public ReconRunDao(JdbcClient jdbc,Clock clock,OutboxWriter outbox){this.jdbc=jdbc;this.clock=clock;this.outbox=outbox;}
    @Transactional
    public void create(UUID tenantId,UUID id,Path file,long bytes,Instant asOf,Integer expected,boolean dryRun,String username) {
        // Serialize upload acceptance per tenant, including the completed-file watermark check.
        jdbc.sql("SELECT id FROM tenant WHERE id=? FOR UPDATE").param(tenantId).query(UUID.class).single();
        Instant last=jdbc.sql("SELECT as_of FROM recon_run WHERE tenant_id=? AND status='COMPLETED' ORDER BY as_of DESC LIMIT 1").param(tenantId)
                .query((row,n)->row.getTimestamp(1).toInstant()).optional().orElse(null);
        if(last!=null && !asOf.isAfter(last)) throw new ConflictException(ErrorCode.RECON_EXPORT_STALE);
        jdbc.sql("""
                INSERT INTO recon_run(id,tenant_id,status,dry_run,file_path,file_size_bytes,as_of,expected_row_count,created_by,created_at,heartbeat_at)
                VALUES (?,?,'UPLOADED',?,?,?,?,?,?,?,?)
                """).params(id,tenantId,dryRun,file.toString(),bytes,Timestamp.from(asOf),expected,username,Timestamp.from(clock.instant()),Timestamp.from(clock.instant())).update();
    }
    public Run get(UUID tenantId,UUID id) {
        return jdbc.sql("SELECT * FROM recon_run WHERE tenant_id=? AND id=?").params(tenantId,id).query((row,n)->new Run(id,tenantId,row.getString("status"),row.getBoolean("dry_run"),
                Path.of(row.getString("file_path")),row.getTimestamp("as_of").toInstant(),(Integer)row.getObject("expected_row_count"),row.getString("created_by"),row.getString("approval_mode"))).optional().orElseThrow(NotFoundException::new);
    }
    public Map<String,Object> metadata(UUID tenantId,UUID id) {
        return jdbc.sql("SELECT * FROM recon_run WHERE tenant_id=? AND id=?").params(tenantId,id).query(new org.springframework.jdbc.core.ColumnMapRowMapper()).optional().orElseThrow(NotFoundException::new);
    }
    @Transactional
    public void transition(UUID tenantId,UUID id,String expected,String next) {
        boolean terminal=Set.of("COMPLETED","FAILED","PLANNED","REJECTED").contains(next);
        int changed=jdbc.sql("""
                UPDATE recon_run SET status=?,heartbeat_at=?,finished_at=CASE WHEN ? THEN ? ELSE finished_at END,
                  diff_started_at=CASE WHEN ?='DIFFING' THEN ? ELSE diff_started_at END
                WHERE tenant_id=? AND id=? AND status=?
                """).params(next,now(),terminal,now(),next,now(),tenantId,id,expected).update();
        if(changed==0) throw new InvalidStateException();
    }
    @Transactional
    public void settings(UUID tenantId,UUID id,BrakeSettings settings) {
        jdbc.sql("UPDATE recon_run SET brake_ratio_used=?,brake_count_used=?,heartbeat_at=? WHERE tenant_id=? AND id=? AND status='DIFFING'")
                .params(settings.maxMissingRatio(),settings.maxMissingCount(),now(),tenantId,id).update();
    }
    @Transactional
    public void progress(UUID tenantId,UUID id,long total,long valid,Long trailer,DiffCounters counts) {
        jdbc.sql("""
                UPDATE recon_run SET rows_total=?,rows_valid=?,rows_rejected=?,trailer_row_count=?,active_before=?,missing_active=?,
                  planned_creates=?,planned_updates=?,planned_heals=?,planned_advances=?,planned_deactivations=?,unchanged=?,stale_skipped=?,heartbeat_at=?
                WHERE tenant_id=? AND id=? AND status='DIFFING'
                """).params(total,valid,total-valid,trailer,counts.activeBefore(),counts.missingActive(),counts.creates(),counts.updates(),counts.heals(),counts.advances(),
                        counts.missingActive(),counts.unchanged(),counts.staleSkipped(),now(),tenantId,id).update();
    }
    @Transactional
    public void finishDiff(UUID tenantId,UUID id,BrakeDecision brake,boolean dryRun) {
        jdbc.sql("UPDATE recon_run SET missing_ratio=?,brake_tripped=?,brake_reason=?,diff_finished_at=?,heartbeat_at=? WHERE tenant_id=? AND id=? AND status='DIFFING'")
                .params(brake.ratio(),brake.tripped(),brake.reason(),now(),now(),tenantId,id).update();
        String next=dryRun?"PLANNED":brake.tripped()?"AWAITING_APPROVAL":"APPLYING";
        transition(tenantId,id,"DIFFING",next);
        if(brake.tripped() && !dryRun) {
            jdbc.sql("INSERT INTO ops_alert(tenant_id,alert_type,reference_id,message,created_at) VALUES (?,'RECON_BRAKE_TRIPPED',?,?,?)")
                    .params(tenantId,id.toString(),brake.message(),now()).update();
            outbox.writeReconBrake(tenantId,id,brake.message(),clock.instant());
        }
    }
    @Transactional
    public void decide(UUID tenantId,UUID id,boolean approve,String mode,String username,String note) {
        transition(tenantId,id,"AWAITING_APPROVAL",approve?"APPLYING":"REJECTED");
        jdbc.sql("UPDATE recon_run SET approval_mode=?,decided_by=?,decided_at=?,decision_note=? WHERE tenant_id=? AND id=?")
                .params(mode,username,now(),note,tenantId,id).update();
        if(!approve) jdbc.sql("UPDATE recon_action SET status='SKIPPED',outcome_reason='RUN_REJECTED' WHERE tenant_id=? AND run_id=? AND status='PENDING'").params(tenantId,id).update();
    }
    @Transactional
    public void fail(UUID tenantId,UUID id,String expected,String reason) {
        transition(tenantId,id,expected,"FAILED");
        jdbc.sql("UPDATE recon_run SET failure_reason=? WHERE tenant_id=? AND id=?").params(reason,tenantId,id).update();
    }
    public List<RunKey> recoverable(Instant olderThan) {
        // Global single-instance recovery inventory; each mutation still carries the row's tenant.
        return jdbc.sql("SELECT tenant_id,id,status FROM recon_run WHERE status IN ('UPLOADED','DIFFING','APPLYING') AND (CAST(? AS timestamptz) IS NULL OR COALESCE(heartbeat_at,created_at)<?)")
                .params(olderThan==null?null:Timestamp.from(olderThan),olderThan==null?null:Timestamp.from(olderThan))
                .query((row,n)->new RunKey(row.getObject(1,UUID.class),row.getObject(2,UUID.class),row.getString(3))).list();
    }
    public Timestamp now(){return Timestamp.from(clock.instant());}
    public record Run(UUID id,UUID tenantId,String status,boolean dryRun,Path file,Instant asOf,Integer expectedRowCount,String createdBy,String approvalMode) { }
    public record RunKey(UUID tenantId,UUID id,String status) { }
}
