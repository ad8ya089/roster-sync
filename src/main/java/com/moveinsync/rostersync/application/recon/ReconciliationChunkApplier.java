package com.moveinsync.rostersync.application.recon;
import com.moveinsync.rostersync.application.ingest.EmployeeChangeApplier;
import com.moveinsync.rostersync.domain.recon.ActionType;
import com.moveinsync.rostersync.infrastructure.fault.*;
import com.moveinsync.rostersync.infrastructure.persistence.ReconActionDao;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReconciliationChunkApplier {
    private final ReconActionDao actions;private final EmployeeChangeApplier employees;private final JdbcClient jdbc;
    private final FaultInjector faults;private final Clock clock;private final EntityManager entities;
    private final com.moveinsync.rostersync.infrastructure.metrics.RosterMetrics metrics;
    public ReconciliationChunkApplier(ReconActionDao actions,EmployeeChangeApplier employees,JdbcClient jdbc,FaultInjector faults,Clock clock,EntityManager entities,com.moveinsync.rostersync.infrastructure.metrics.RosterMetrics metrics) {
        this.actions=actions;this.employees=employees;this.jdbc=jdbc;this.faults=faults;this.clock=clock;this.entities=entities;this.metrics=metrics;
    }
    @Transactional
    public int applyNext(UUID tenantId,UUID runId,int size,String mode) {
        var pending=actions.pending(tenantId,runId,size,true);int applied=0,superseded=0;
        for(var action:pending) {
            String outcome=action.type()==ActionType.DEACTIVATE && "SKIP_DEACTIVATIONS".equals(mode)?"SKIPPED":employees.applySnapshot(tenantId,action);
            actions.complete(tenantId,runId,action.id(),outcome,null);
            metrics.action(action.type().name(),outcome);
            if(outcome.equals("APPLIED"))applied++;if(outcome.equals("SUPERSEDED"))superseded++;
            faults.hit(FaultPoint.RECON_AFTER_N_ACTIONS);
        }
        jdbc.sql("UPDATE recon_run SET applied=applied+?,superseded=superseded+?,heartbeat_at=? WHERE tenant_id=? AND id=? AND status='APPLYING'")
                .params(applied,superseded,Timestamp.from(clock.instant()),tenantId,runId).update();
        entities.flush();entities.clear();return pending.size();
    }
    @Transactional
    public void markFailed(UUID tenantId,UUID runId,long actionId,String errorClass,String auditCodes) {
        var employeeId=jdbc.sql("SELECT external_id FROM recon_action WHERE tenant_id=? AND run_id=? AND id=? FOR UPDATE").params(tenantId,runId,actionId).query(String.class).single();
        actions.complete(tenantId,runId,actionId,"FAILED",errorClass);
        var type=jdbc.sql("SELECT action FROM recon_action WHERE tenant_id=? AND run_id=? AND id=?").params(tenantId,runId,actionId).query(String.class).single();
        metrics.action(type,"FAILED");
        jdbc.sql("INSERT INTO record_rejection(tenant_id,source,run_id,external_id,error_codes,created_at) VALUES (?,'FILE',?,?,?,?)")
                .params(tenantId,runId,employeeId,auditCodes.length()>500?"record:APPLY_FAILED":auditCodes,Timestamp.from(clock.instant())).update();
        com.moveinsync.rostersync.infrastructure.metrics.RosterMetrics.committed(()->{
            metrics.fileRowRejected();java.util.Arrays.stream(auditCodes.split(",")).map(code->code.substring(code.lastIndexOf(':')+1)).distinct().forEach(code->metrics.rejection(code,"file"));
        });
        jdbc.sql("UPDATE recon_run SET heartbeat_at=? WHERE tenant_id=? AND id=? AND status='APPLYING'").params(Timestamp.from(clock.instant()),tenantId,runId).update();
    }
}
