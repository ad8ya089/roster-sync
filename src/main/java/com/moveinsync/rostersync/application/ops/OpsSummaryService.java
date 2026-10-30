package com.moveinsync.rostersync.application.ops;
import com.moveinsync.rostersync.infrastructure.metrics.RosterMetrics;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OpsSummaryService {
    private final JdbcClient jdbc;private final Clock clock;private final RosterMetrics metrics;
    public OpsSummaryService(JdbcClient jdbc,Clock clock,RosterMetrics metrics){this.jdbc=jdbc;this.clock=clock;this.metrics=metrics;}
    @Transactional(readOnly=true)
    public Summary get(UUID tenantId){
        var now=clock.instant();var cutoff=Timestamp.from(now.minus(Duration.ofMinutes(15)));
        var outcomes=new HashMap<String,Long>();jdbc.sql("SELECT outcome,count(*) FROM hr_event_receipt WHERE tenant_id=? AND received_at>=? GROUP BY outcome")
                .params(tenantId,cutoff).query((row,n)->{outcomes.put(row.getString(1),row.getLong(2));return 0;}).list();
        var rejected=jdbc.sql("SELECT count(*) FILTER(WHERE error_codes NOT LIKE '%VERSION_CONFLICT%'),count(*) FILTER(WHERE error_codes LIKE '%VERSION_CONFLICT%') FROM record_rejection WHERE tenant_id=? AND created_at>=?")
                .params(tenantId,cutoff).query((row,n)->new long[]{row.getLong(1),row.getLong(2)}).single();
        var outbox=jdbc.sql("SELECT count(*) FILTER(WHERE status='PENDING'),count(*) FILTER(WHERE status='DEAD'),min(created_at) FILTER(WHERE status='PENDING'),count(*) FILTER(WHERE status='PENDING' AND attempts>0) FROM outbox_event WHERE tenant_id=?")
                .param(tenantId).query((row,n)->new Outbox(row.getLong(1),row.getLong(2),row.getTimestamp(3)==null?0:Math.max(0,Duration.between(row.getTimestamp(3).toInstant(),now).toMillis()/1000.0),row.getLong(4))).single();
        long waiting=jdbc.sql("SELECT count(*) FROM recon_run WHERE tenant_id=? AND status='AWAITING_APPROVAL'").param(tenantId).query(Long.class).single();
        long alerts=jdbc.sql("SELECT count(*) FROM ops_alert WHERE tenant_id=? AND acknowledged_at IS NULL").param(tenantId).query(Long.class).single();
        var last=jdbc.sql("SELECT id,status,brake_reason,missing_active FROM recon_run WHERE tenant_id=? ORDER BY created_at DESC LIMIT 1").param(tenantId)
                .query((row,n)->new LastRun(row.getObject(1,UUID.class),row.getString(2),row.getString(3),(Integer)row.getObject(4))).optional().orElse(null);
        return new Summary(now,new Durable(new Outcomes(outcomes.getOrDefault("APPLIED",0L),outcomes.getOrDefault("STALE",0L),outcomes.getOrDefault("NO_CHANGE",0L),rejected[0],rejected[1]),outbox,new Recon(waiting,last),alerts),
                new SinceRestart(metrics.since(),true,(long)metrics.duplicates(),(long)metrics.lockRetries(),(long)metrics.publishFailures(),metrics.p99ApplyMs(),metrics.p99LagSeconds()));
    }
    public record Summary(Instant generatedAt,Durable durable,SinceRestart sinceRestart) { }
    public record Durable(Outcomes last15m,Outbox outbox,Recon reconciliation,long openAlerts) { }
    public record Outcomes(long applied,long stale,long noChange,long rejected,long conflict) { }
    public record Outbox(long pending,long dead,double oldestPendingAgeSeconds,long currentlyRetrying) { }
    public record Recon(long awaitingApproval,LastRun lastRun) { }
    public record LastRun(UUID runId,String status,String brakeReason,Integer missingActive) { }
    public record SinceRestart(Instant since,boolean instanceWide,long duplicates,long lockRetries,long outboxPublishFailures,Double p99ApplyMs,Double lagP99Seconds) { }
}
