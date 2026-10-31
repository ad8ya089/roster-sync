package com.moveinsync.rostersync.application.ingest;
import com.moveinsync.rostersync.domain.command.UpdateEmployee;
import com.moveinsync.rostersync.domain.model.*;
import com.moveinsync.rostersync.domain.validation.ValidationResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.postgresql.util.*;
import org.springframework.dao.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class EventIngestionServiceTest {
    @Test void validationDatabaseErrorsAreAlsoIsolatedPerRecord() {
        prepare();when(parser.parse(eq(tenant),any())).thenThrow(new DataIntegrityViolationException("CANARY"))
                .thenReturn(new HrEventInputParser.ParsedEvent(command,"E1",2,"UPDATE",new ValidationResult(List.of(),List.of())));
        when(applier.apply(eq(tenant),eq(command),anyList())).thenReturn(ApplyResult.outcome(IngestOutcome.APPLIED));
        var result=service.ingest(tenant,List.of(Map.of(),Map.of()));
        assertThat(result.summary().rejected()).isEqualTo(1);assertThat(result.summary().applied()).isEqualTo(1);
    }
    @Test void unexpectedRecordFailuresAreAuditedAndLaterRecordsContinue() {
        prepare();when(applier.apply(eq(tenant),eq(command),anyList()))
                .thenReturn(ApplyResult.outcome(IngestOutcome.APPLIED),ApplyResult.outcome(IngestOutcome.APPLIED))
                .thenThrow(new DataIntegrityViolationException("CANARY_VALUE"))
                .thenReturn(ApplyResult.outcome(IngestOutcome.APPLIED),ApplyResult.outcome(IngestOutcome.APPLIED));
        var result=service.ingest(tenant,Collections.nCopies(5,Map.of()));
        assertThat(result.summary().applied()).isEqualTo(4);assertThat(result.summary().rejected()).isEqualTo(1);
        verify(audit).write(eq(tenant),eq("E1"),eq(2L),argThat(errors->errors.getFirst().code().equals("UNPROCESSABLE_RECORD")));
    }
    @Test void lostConnectionStopsTheBatchWithoutMisclassifyingItAsARecordRejection() {
        prepare();when(applier.apply(eq(tenant),eq(command),anyList()))
                .thenReturn(ApplyResult.outcome(IngestOutcome.APPLIED),ApplyResult.outcome(IngestOutcome.APPLIED))
                .thenThrow(new org.springframework.transaction.CannotCreateTransactionException("safe",new java.sql.SQLException("CANARY","08006")));
        assertThatThrownBy(()->service.ingest(tenant,Collections.nCopies(5,Map.of())))
                .isInstanceOf(DataAccessResourceFailureException.class);
        verify(applier,times(3)).apply(eq(tenant),eq(command),anyList());verifyNoInteractions(audit);
    }
    private final UUID tenant=UUID.randomUUID();
    private final HrEventInputParser parser=mock(HrEventInputParser.class);
    private final EmployeeChangeApplier applier=mock(EmployeeChangeApplier.class);
    private final RejectionWriter audit=mock(RejectionWriter.class);
    private final SimpleMeterRegistry metrics=new SimpleMeterRegistry();
    private final EventIngestionService service=new EventIngestionService(parser,applier,audit,new com.moveinsync.rostersync.infrastructure.metrics.RosterMetrics(metrics,java.time.Clock.systemUTC()));
    private final UpdateEmployee command=new UpdateEmployee("E1",2,null,null,HrSnapshot.tombstone());
    private void prepare() {
        when(parser.parse(eq(tenant),any())).thenReturn(new HrEventInputParser.ParsedEvent(command,"E1",2,"UPDATE",new ValidationResult(List.of(),List.of())));
    }
    @Test void retriesLockFailuresAtMostThreeTimesOutsideTheTransaction() {
        prepare();
        when(applier.apply(eq(tenant),eq(command),anyList())).thenThrow(new CannotAcquireLockException("safe"));
        assertThat(service.ingest(tenant,List.of(Map.of())).summary().retryableError()).isEqualTo(1);
        verify(applier,times(4)).apply(eq(tenant),eq(command),anyList());
        assertThat(metrics.get("roster.apply.retries").tag("reason","lock_timeout").counter().count()).isEqualTo(3);
        verifyNoInteractions(audit);
    }
    @Test void retriesAConcurrentCreateExactlyOnceAndCountsNoChangeSeparately() {
        prepare();
        var sql=mock(PSQLException.class); var detail=mock(ServerErrorMessage.class);
        when(sql.getSQLState()).thenReturn("23505"); when(sql.getServerErrorMessage()).thenReturn(detail);
        when(detail.getConstraint()).thenReturn("employee_tenant_id_external_id_key");
        when(applier.apply(eq(tenant),eq(command),anyList())).thenThrow(new DataIntegrityViolationException("safe",sql)).thenReturn(ApplyResult.outcome(IngestOutcome.NO_CHANGE));
        assertThat(service.ingest(tenant,List.of(Map.of())).summary().noChange()).isEqualTo(1);
        verify(applier,times(2)).apply(eq(tenant),eq(command),anyList());
        assertThat(metrics.get("roster.events").tag("outcome","no_change").tag("type","UPDATE").counter().count()).isEqualTo(1);
    }
    @Test void aConflictAuditsAfterTheProxyHasRolledBackAndNeverRetries() {
        prepare(); when(applier.apply(eq(tenant),eq(command),anyList())).thenThrow(new RecordConflictException(7));
        var result=service.ingest(tenant,List.of(Map.of()));
        assertThat(result.summary().conflict()).isEqualTo(1); assertThat(result.results().getFirst().currentVersion()).isEqualTo(7);
        verify(applier).apply(eq(tenant),eq(command),anyList());
        verify(audit).write(eq(tenant),eq("E1"),eq(2L),argThat(errors->errors.size()==1 && errors.getFirst().code().equals("VERSION_CONFLICT")));
    }
}
