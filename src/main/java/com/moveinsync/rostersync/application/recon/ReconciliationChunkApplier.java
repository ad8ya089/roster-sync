package com.moveinsync.rostersync.application.recon;
import com.moveinsync.rostersync.application.ingest.EmployeeChangeApplier;
import com.moveinsync.rostersync.domain.recon.ActionType;
import com.moveinsync.rostersync.infrastructure.fault.*;
import com.moveinsync.rostersync.infrastructure.persistence.ReconActionDao;
import com.moveinsync.rostersync.infrastructure.persistence.EmployeeRepository;
import com.moveinsync.rostersync.infrastructure.persistence.OfficeRepository;
import com.moveinsync.rostersync.infrastructure.persistence.EmployeeEntity;
import com.moveinsync.rostersync.infrastructure.persistence.OfficeEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.FlushModeType;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;
import com.moveinsync.rostersync.application.outbox.OutboxWriter;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReconciliationChunkApplier {
    private final ReconActionDao actions;private final EmployeeChangeApplier employees;private final JdbcClient jdbc;
    private final FaultInjector faults;private final Clock clock;private final EntityManager entities;
    private final com.moveinsync.rostersync.infrastructure.metrics.RosterMetrics metrics;
    private final OutboxWriter outbox;
    private final EmployeeRepository employeeRows;private final OfficeRepository officeRows;
    public ReconciliationChunkApplier(ReconActionDao actions,EmployeeChangeApplier employees,JdbcClient jdbc,FaultInjector faults,Clock clock,EntityManager entities,com.moveinsync.rostersync.infrastructure.metrics.RosterMetrics metrics,OutboxWriter outbox,EmployeeRepository employeeRows,OfficeRepository officeRows) {
        this.actions=actions;this.employees=employees;this.jdbc=jdbc;this.faults=faults;this.clock=clock;this.entities=entities;this.metrics=metrics;this.outbox=outbox;this.employeeRows=employeeRows;this.officeRows=officeRows;
    }
    @Transactional
    public int applyNext(UUID tenantId,UUID runId,int size,String mode) {
        var pending = actions.pending(tenantId, runId, size, true);
        if (pending.isEmpty()) return 0;
        int applied = 0, superseded = 0;
        var events = new ArrayList<OutboxWriter.PendingEvent>();
        var outcomes = new LinkedHashMap<Long, String>();
        var previousFlush = entities.getFlushMode();
        // AUTO would flush each prior employee before the next locking query, defeating
        // batching. Locked rows remain authoritative; flush once before the JDBC writes.
        entities.setFlushMode(FlushModeType.COMMIT);
        try {
            var applicable = pending.stream().filter(action -> !(action.type() == ActionType.DEACTIVATE && "SKIP_DEACTIVATIONS".equals(mode))).toList();
            Map<String, EmployeeEntity> locked = applicable.isEmpty() ? Map.of() : employeeRows.findAllForUpdate(tenantId,
                    applicable.stream().map(ReconActionDao.Action::employeeId).toList()).stream()
                    .collect(java.util.stream.Collectors.toMap(EmployeeEntity::getExternalId, employee -> employee));
            var codes = applicable.stream().filter(action -> action.snapshot() != null).map(action -> action.snapshot().officeCode())
                    .filter(Objects::nonNull).collect(java.util.stream.Collectors.toSet());
            var offices = new HashMap<String, OfficeEntity>();
            // Fresh transaction-local plain reads, never Caffeine. Refresh once per distinct
            // submitted office; deactivation after this read remains the documented benign race.
            if (!codes.isEmpty()) for (var office : officeRows.findAllByTenantIdAndCodeIn(tenantId, codes)) {
                officeRows.refreshPlain(tenantId, office);
                offices.put(office.getCode(), office);
            }
            for (var action : pending) {
                String previous = org.slf4j.MDC.get("employeeId");
                org.slf4j.MDC.put("employeeId", action.employeeId());
                try {
                    String outcome = action.type() == ActionType.DEACTIVATE && "SKIP_DEACTIVATIONS".equals(mode) ? "SKIPPED"
                            : employees.applySnapshot(tenantId, action, locked.get(action.employeeId()), offices, events);
                    outcomes.put(action.id(), outcome);
                    metrics.action(action.type().name(), outcome);
                    if (outcome.equals("APPLIED")) applied++;
                    if (outcome.equals("SUPERSEDED")) superseded++;
                    faults.hit(FaultPoint.RECON_AFTER_N_ACTIONS);
                } finally {
                    if (previous == null) org.slf4j.MDC.remove("employeeId"); else org.slf4j.MDC.put("employeeId", previous);
                }
            }
            entities.flush();
            outbox.writeBatch(events);
            actions.completeBatch(tenantId, runId, outcomes);
            jdbc.sql("UPDATE recon_run SET applied=applied+?,superseded=superseded+?,heartbeat_at=? WHERE tenant_id=? AND id=? AND status='APPLYING'")
                    .params(applied, superseded, Timestamp.from(clock.instant()), tenantId, runId).update();
            entities.clear();
            return pending.size();
        } finally {
            entities.setFlushMode(previousFlush);
        }
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
