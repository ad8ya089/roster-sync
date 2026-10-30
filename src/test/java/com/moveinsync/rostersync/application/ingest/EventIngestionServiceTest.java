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
