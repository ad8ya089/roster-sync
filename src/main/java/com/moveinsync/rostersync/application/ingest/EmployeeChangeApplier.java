package com.moveinsync.rostersync.application.ingest;
import com.moveinsync.rostersync.application.outbox.OutboxWriter;
import com.moveinsync.rostersync.config.RosterProperties;
import com.moveinsync.rostersync.domain.command.*;
import com.moveinsync.rostersync.domain.decision.*;
import com.moveinsync.rostersync.domain.hash.RecordHasher;
import com.moveinsync.rostersync.domain.model.*;
import com.moveinsync.rostersync.domain.recon.BucketPartitioner;
import com.moveinsync.rostersync.domain.validation.FieldError;
import com.moveinsync.rostersync.infrastructure.fault.*;
import com.moveinsync.rostersync.infrastructure.persistence.*;
import java.time.Clock;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EmployeeChangeApplier {
    private final EmployeeRepository employees;
    private final OfficeRepository offices;
    private final HrEventReceiptDao receipts;
    private final ChangeDecider decider;
    private final RecordHasher hasher;
    private final OutboxWriter outbox;
    private final FaultInjector faults;
    private final Clock clock;
    private final int buckets;
    public EmployeeChangeApplier(EmployeeRepository employees, OfficeRepository offices, HrEventReceiptDao receipts,
                                 ChangeDecider decider, RecordHasher hasher, OutboxWriter outbox, FaultInjector faults,
                                 Clock clock, RosterProperties properties) {
        this.employees=employees; this.offices=offices; this.receipts=receipts; this.decider=decider; this.hasher=hasher;
        this.outbox=outbox; this.faults=faults; this.clock=clock; buckets=properties.reconciliation().buckets();
    }

    @Transactional
    public ApplyResult apply(UUID tenantId, HrCommand command, List<FieldError> warnings) {
        var now = clock.instant();
        String hash = hasher.hashEvent(command);
        String warningCodes = warnings.stream().map(FieldError::code).distinct().sorted().collect(Collectors.joining(","));
        if (!receipts.reserve(tenantId, command, hash, warningCodes, now)) {
            var existing = receipts.find(tenantId, command.employeeId(), command.version());
            if (!existing.hash().equals(hash)) throw new RecordConflictException(employees.findByTenantIdAndExternalId(tenantId, command.employeeId())
                    .map(EmployeeEntity::getHrVersion).orElse(command.version()));
            return new ApplyResult(IngestOutcome.DUPLICATE, null, existing.outcome());
        }
        var employee = employees.findForUpdate(tenantId, command.employeeId()).orElse(null);
        var current = employee == null ? CurrentEmployeeState.missing() : employee.currentState();
        ApplyDecision decision = decider.decide(current, command, DecisionMode.EVENT);
        ApplyResult result = switch (decision) {
            case ApplyDecision.Apply change -> {
                OfficeEntity office = null;
                if (command.type() == HrEventType.DEACTIVATE && employee != null) {
                    employee.applyHrSnapshot(change.newSnapshot(), command.version(), now);
                } else {
                    String code = change.newSnapshot().officeCode();
                    if (code != null) {
                        office = offices.findByTenantIdAndCode(tenantId, code).orElseThrow(() -> rejected("UNKNOWN_OFFICE"));
                        offices.refreshPlain(tenantId, office);
                        if (!office.isActive()) throw rejected("INACTIVE_OFFICE");
                    }
                    if (employee == null) {
                        employee = new EmployeeEntity(tenantId, command.employeeId(), BucketPartitioner.bucketOf(command.employeeId(), buckets),
                                change.newSnapshot(), command.version(), now, office);
                        // A concurrent creator's unique violation aborts this whole tx, receipt included.
                        // The non-transactional caller retries apply(), never a statement in this aborted tx.
                        employees.saveAndFlush(tenantId, employee);
                    } else employee.applyHrSnapshot(change.newSnapshot(), command.version(), now, office);
                }
                employees.flush(tenantId);
                outbox.write(tenantId, command.employeeId(), command.version(), change.eventType(), "API", change.changedFields(), change.newSnapshot(), now);
                // After both writes, before commit: rollback tests exercise JDBC + JPA + receipt atomicity.
                faults.hit(FaultPoint.APPLY_AFTER_EMPLOYEE_WRITE);
                yield ApplyResult.outcome(IngestOutcome.APPLIED);
            }
            case ApplyDecision.VersionAdvanced advanced -> {
                // Recheck a submitted office even on identical updates; DEACTIVATE submits no office.
                if (command.type() != HrEventType.DEACTIVATE && current.snapshot().officeCode() != null) {
                    var office = offices.findByTenantIdAndCode(tenantId, current.snapshot().officeCode()).orElseThrow(() -> rejected("UNKNOWN_OFFICE"));
                    offices.refreshPlain(tenantId, office);
                    if (!office.isActive()) throw rejected("INACTIVE_OFFICE");
                }
                employee.advanceHrVersion(advanced.newVersion(), now);
                employees.flush(tenantId);
                faults.hit(FaultPoint.APPLY_AFTER_EMPLOYEE_WRITE);
                yield ApplyResult.outcome(IngestOutcome.NO_CHANGE);
            }
            case ApplyDecision.Stale stale -> new ApplyResult(IngestOutcome.STALE, stale.currentVersion(), null);
            case ApplyDecision.Duplicate ignored -> new ApplyResult(IngestOutcome.DUPLICATE, null, IngestOutcome.DUPLICATE);
            case ApplyDecision.Conflict conflict -> throw new RecordConflictException(conflict.currentVersion());
        };
        receipts.complete(tenantId, command.employeeId(), command.version(), result.outcome());
        return result;
    }

    private static RecordRejectedException rejected(String code) { return new RecordRejectedException(List.of(new FieldError("officeCode", code))); }

    /** Joins the caller's chunk transaction. No receipts and no nested lock retry loop. */
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public String applySnapshot(UUID tenantId,com.moveinsync.rostersync.infrastructure.persistence.ReconActionDao.Action action) {
        var employee=employees.findForUpdate(tenantId,action.employeeId()).orElse(null);
        if(action.expectedVersion()==null && employee!=null || action.expectedVersion()!=null &&
                (employee==null || employee.getHrVersion()>action.expectedVersion())) return "SUPERSEDED";
        var now=clock.instant();
        if(action.type()==com.moveinsync.rostersync.domain.recon.ActionType.DEACTIVATE) {
            if(employee==null || employee.getHrVersion()!=action.expectedVersion() || !"ACTIVE".equals(employee.snapshot().status())) return "SUPERSEDED";
            employee.markDeactivatedByRecon(now);employees.flush(tenantId);
            outbox.write(tenantId,action.employeeId(),employee.getHrVersion(),com.moveinsync.rostersync.domain.outbox.OutboxEventType.DEACTIVATED,
                    "RECON",List.of(EmployeeField.STATUS),employee.snapshot(),now);return "APPLIED";
        }
        var command=new UpdateEmployee(action.employeeId(),action.fileVersion(),null,null,action.snapshot());
        var decision=decider.decide(employee==null?CurrentEmployeeState.missing():employee.currentState(),command,DecisionMode.SNAPSHOT);
        switch(decision) {
            case ApplyDecision.Apply change -> {
                OfficeEntity office=null;
                if(change.newSnapshot().officeCode()!=null) {
                    office=offices.findByTenantIdAndCode(tenantId,change.newSnapshot().officeCode()).orElseThrow(()->rejected("UNKNOWN_OFFICE"));
                    offices.refreshPlain(tenantId,office);if(!office.isActive()) throw rejected("INACTIVE_OFFICE");
                }
                if(employee==null) employees.saveAndFlush(tenantId,new EmployeeEntity(tenantId,action.employeeId(),BucketPartitioner.bucketOf(action.employeeId(),buckets),change.newSnapshot(),action.fileVersion(),now,office));
                else employee.applyHrSnapshot(change.newSnapshot(),action.fileVersion(),now,office);
                employees.flush(tenantId);outbox.write(tenantId,action.employeeId(),action.fileVersion(),change.eventType(),"RECON",change.changedFields(),change.newSnapshot(),now);
            }
            case ApplyDecision.VersionAdvanced advanced -> {employee.advanceHrVersion(advanced.newVersion(),now);employees.flush(tenantId);}
            case ApplyDecision.Duplicate ignored -> {return "SUPERSEDED";}
            case ApplyDecision.Stale ignored -> {return "SUPERSEDED";}
            case ApplyDecision.Conflict ignored -> throw new IllegalStateException("Snapshot conflict");
        }
        return "APPLIED";
    }
}
