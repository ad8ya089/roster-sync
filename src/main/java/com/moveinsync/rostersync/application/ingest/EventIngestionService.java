package com.moveinsync.rostersync.application.ingest;
import com.moveinsync.rostersync.api.error.*;
import com.moveinsync.rostersync.domain.model.IngestOutcome;
import com.moveinsync.rostersync.domain.validation.FieldError;
import com.moveinsync.rostersync.infrastructure.fault.SimulatedCrashException;
import com.moveinsync.rostersync.infrastructure.persistence.PostgresConstraints;
import com.moveinsync.rostersync.infrastructure.metrics.RosterMetrics;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.LockSupport;
import org.slf4j.*;
import org.springframework.dao.*;
import org.springframework.stereotype.Service;

@Service
public class EventIngestionService {
    private static final Logger LOG = LoggerFactory.getLogger(EventIngestionService.class);
    private final HrEventInputParser parser;
    private final EmployeeChangeApplier applier;
    private final RejectionWriter rejections;
    private final RosterMetrics metrics;
    public EventIngestionService(HrEventInputParser parser, EmployeeChangeApplier applier, RejectionWriter rejections, RosterMetrics metrics) {
        this.parser=parser; this.applier=applier; this.rejections=rejections; this.metrics=metrics;
    }

    // Deliberately not transactional: each proxy call owns its tx, and every retry starts a fresh tx.
    public IngestResult ingest(UUID tenantId, List<?> inputs) {
        if (inputs == null || inputs.isEmpty()) throw new RequestValidationException(ErrorCode.VALIDATION_FAILED, List.of(new FieldError("events", "MISSING_REQUIRED_FIELD")));
        if (inputs.size() > 1000) throw new RequestValidationException(ErrorCode.BATCH_TOO_LARGE, List.of(new FieldError("events", "BATCH_TOO_LARGE")));
        var results = new ArrayList<IngestResult.RecordResult>();
        for (int index = 0; index < inputs.size(); index++) {
            var parsed = parseSafely(tenantId, inputs.get(index));
            if (parsed.employeeId() != null) MDC.put("employeeId", parsed.employeeId());
            try {
                long started=System.nanoTime();
                var result = process(tenantId, index, parsed);
                results.add(result);
                metrics.event(result.outcome().metricTag(),parsed.type(),System.nanoTime()-started,parsed.command()==null?null:parsed.command().occurredAt());
                if (result.outcome() == IngestOutcome.CONFLICT) LOG.warn("hr.event.conflict employeeId={} version={} currentVersion={}", parsed.employeeId(), parsed.version(), result.currentVersion());
                else if (result.outcome() == IngestOutcome.APPLIED) LOG.info("hr.event.applied employeeId={} version={} outcome={}", parsed.employeeId(), parsed.version(), result.outcome());
            } catch(RuntimeException failure) {
                if(connectionFailure(failure)) throw new DataAccessResourceFailureException("Database unavailable");
                throw failure;
            } finally { MDC.remove("employeeId"); }
        }
        var counts = new EnumMap<IngestOutcome, Long>(IngestOutcome.class);
        for (var result : results) counts.merge(result.outcome(), 1L, Long::sum);
        return new IngestResult(new IngestResult.Summary(inputs.size(), count(counts, IngestOutcome.APPLIED), count(counts, IngestOutcome.NO_CHANGE),
                count(counts, IngestOutcome.DUPLICATE), count(counts, IngestOutcome.STALE), count(counts, IngestOutcome.CONFLICT),
                count(counts, IngestOutcome.REJECTED), count(counts, IngestOutcome.RETRYABLE_ERROR)), List.copyOf(results));
    }
    private HrEventInputParser.ParsedEvent parseSafely(UUID tenantId,Object input) {
        try{return parser.parse(tenantId,input);}
        catch(RuntimeException failure) {
            if(connectionFailure(failure)) throw new DataAccessResourceFailureException("Database unavailable");
            String id=input instanceof Map<?,?> map && map.get("employeeId") instanceof String value && value.matches("[A-Za-z0-9_-]{1,64}")?value:null;
            LOG.warn("hr.event.rejected employeeId={} exceptionClass={}",id,failure.getClass().getSimpleName());
            return new HrEventInputParser.ParsedEvent(null,id,0,"UNKNOWN",new com.moveinsync.rostersync.domain.validation.ValidationResult(
                    List.of(new FieldError("record","UNPROCESSABLE_RECORD")),List.of()));
        }
    }
    private IngestResult.RecordResult process(UUID tenantId, int index, HrEventInputParser.ParsedEvent parsed) {
        if (!parsed.validation().valid()) {
            rejections.write(tenantId, parsed.employeeId(), parsed.version(), parsed.validation().errors());
            return result(index, parsed, ApplyResult.outcome(IngestOutcome.REJECTED), parsed.validation().errors());
        }
        int createRetries=0, lockRetries=0;
        while (true) {
            try { return result(index, parsed, applier.apply(tenantId, parsed.command(), parsed.validation().warnings()), List.of()); }
            catch (RecordConflictException conflict) {
                var errors = List.of(new FieldError("version", "VERSION_CONFLICT"));
                rejections.write(tenantId, parsed.employeeId(), parsed.version(), errors);
                return result(index, parsed, new ApplyResult(IngestOutcome.CONFLICT, conflict.currentVersion(), null), errors);
            } catch (RecordRejectedException rejected) {
                rejections.write(tenantId, parsed.employeeId(), parsed.version(), rejected.errors());
                return result(index, parsed, ApplyResult.outcome(IngestOutcome.REJECTED), rejected.errors());
            } catch (DataIntegrityViolationException collision) {
                if (!PostgresConstraints.uniqueViolation(collision, "employee_tenant_id_external_id_key")) return unprocessable(tenantId,index,parsed,collision);
                if (createRetries++ >= 1) return retryable(index, parsed);
                metrics.retry("unique_race");
            } catch (PessimisticLockingFailureException locked) {
                if (lockRetries++ >= 3) return retryable(index, parsed);
                metrics.retry("lock_timeout");
                LockSupport.parkNanos(java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(ThreadLocalRandom.current().nextInt(20,101)));
                if (Thread.currentThread().isInterrupted()) return retryable(index, parsed);
            } catch (SimulatedCrashException failure) { return retryable(index, parsed); }
            catch (RuntimeException failure) {
                if(connectionFailure(failure)) throw new DataAccessResourceFailureException("Database unavailable");
                return unprocessable(tenantId,index,parsed,failure);
            }
        }
    }
    private IngestResult.RecordResult unprocessable(UUID tenantId,int index,HrEventInputParser.ParsedEvent parsed,RuntimeException failure) {
        if(connectionFailure(failure)) throw new DataAccessResourceFailureException("Database unavailable");
        // The applier proxy has already rolled back. Audit in its independent short transaction.
        LOG.warn("hr.event.rejected employeeId={} exceptionClass={}",parsed.employeeId(),failure.getClass().getSimpleName());
        var errors=List.of(new FieldError("record","UNPROCESSABLE_RECORD"));
        rejections.write(tenantId,parsed.employeeId(),parsed.version(),errors);
        return result(index,parsed,ApplyResult.outcome(IngestOutcome.REJECTED),errors);
    }
    private static boolean connectionFailure(Throwable failure) {
        var pending=new ArrayDeque<Throwable>();pending.add(failure);
        var seen=Collections.newSetFromMap(new IdentityHashMap<Throwable,Boolean>());
        while(!pending.isEmpty()) {
            var cause=pending.removeFirst();if(!seen.add(cause))continue;
            if(cause instanceof DataAccessResourceFailureException || cause instanceof org.springframework.transaction.CannotCreateTransactionException
                    || cause instanceof org.hibernate.exception.JDBCConnectionException || cause instanceof org.hibernate.TransactionException) return true;
            if(cause.getCause()!=null)pending.add(cause.getCause());
            Collections.addAll(pending,cause.getSuppressed());
            if(cause instanceof java.sql.SQLException sql) {
                if(sql.getSQLState()!=null && sql.getSQLState().startsWith("08")) return true;
                if(sql.getNextException()!=null) pending.add(sql.getNextException());
            }
        }
        return false;
    }
    private static long count(Map<IngestOutcome,Long> counts, IngestOutcome outcome) { return counts.getOrDefault(outcome, 0L); }
    private static IngestResult.RecordResult retryable(int index, HrEventInputParser.ParsedEvent parsed) {
        return result(index, parsed, ApplyResult.outcome(IngestOutcome.RETRYABLE_ERROR), List.of(new FieldError("record", "RETRYABLE_ERROR")));
    }
    private static IngestResult.RecordResult result(int index, HrEventInputParser.ParsedEvent parsed, ApplyResult applied, List<FieldError> errors) {
        return new IngestResult.RecordResult(index, parsed.employeeId(), parsed.version(), applied.outcome(), applied.currentVersion(), applied.originalOutcome(), parsed.validation().warnings(), errors);
    }
}
