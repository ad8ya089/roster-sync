package com.moveinsync.rostersync;

import com.moveinsync.rostersync.application.outbox.OutboxWriter;
import com.moveinsync.rostersync.application.recon.ReconciliationChunkApplier;
import com.moveinsync.rostersync.domain.model.*;
import com.moveinsync.rostersync.domain.recon.*;
import com.moveinsync.rostersync.infrastructure.fault.SimulatedCrashException;
import com.moveinsync.rostersync.infrastructure.persistence.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Timeout(30)
class ReconciliationChunkIT extends AbstractReconciliationIntegrationTest {
    @Autowired ReconciliationChunkApplier chunks; @Autowired ReconActionDao actionDao;
    @MockitoSpyBean com.moveinsync.rostersync.application.ingest.EmployeeChangeApplier snapshotApplier;
    @MockitoSpyBean OutboxWriter outbox;
    private UUID planned() throws Exception {
        removeFixtureEmployees();send(event("A",1,"CREATE","BLR-1","S1"));send(event("B",1,"CREATE","BLR-1","S1"));
        UUID id=UUID.randomUUID();Path file=Files.createTempFile(temporary,"chunk-",".csv");runs.create(JwtTestTokens.ACME,id,file,0,Instant.now(),null,false,"fixture");
        actionDao.insert(JwtTestTokens.ACME,id,List.of("A","B").stream().map(employee->new PlannedAction(employee,ActionType.UPDATE,3L,1L,new HrSnapshot("Fixture Person","fixture@example.test","+919800000001",null,"BLR-1","S2","ACTIVE"))).toList());
        runs.transition(JwtTestTokens.ACME,id,"UPLOADED","DIFFING");runs.transition(JwtTestTokens.ACME,id,"DIFFING","APPLYING");return id;
    }
    @Test void batchedOutboxAndEmployeeChangesRollBackWithTheirActionStates() throws Exception {
        UUID run=planned();long before=employeeOutbox();
        OutboxWriter target=org.springframework.test.util.AopTestUtils.getUltimateTargetObject(outbox);
        doAnswer(call->{call.callRealMethod();throw new SimulatedCrashException();}).when(target).writeBatch(anyList());
        assertThatThrownBy(()->chunks.applyNext(JwtTestTokens.ACME,run,50,"ALL")).isInstanceOf(SimulatedCrashException.class);
        assertThat(version("A")).isEqualTo(1);assertThat(version("B")).isEqualTo(1);assertThat(employeeOutbox()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM recon_action WHERE run_id=? AND status='PENDING'",Long.class,run)).isEqualTo(2);
        assertThat(runs.metadata(JwtTestTokens.ACME,run).get("applied")).isEqualTo(0);
    }
    @Test void employeeLocksPreventAnEventFromInterleavingInsideTheChunk() throws Exception {
        UUID run=planned();var locked=new CountDownLatch(1);var release=new CountDownLatch(1);
        com.moveinsync.rostersync.application.ingest.EmployeeChangeApplier target=org.springframework.test.util.AopTestUtils.getUltimateTargetObject(snapshotApplier);
        doAnswer(call->{locked.countDown();if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException("Lock test timeout");return call.callRealMethod();})
                .when(target).applySnapshot(eq(JwtTestTokens.ACME),any(ReconActionDao.Action.class),any(EmployeeEntity.class),anyMap(),anyList());
        try(var pool=Executors.newFixedThreadPool(2)){
            var chunk=pool.submit(()->chunks.applyNext(JwtTestTokens.ACME,run,50,"ALL"));assertThat(locked.await(10,TimeUnit.SECONDS)).isTrue();
            var event=pool.submit(()->send(event("A",2,"UPDATE","BLR-1","S3")));
            try{
                await().atMost(Duration.ofSeconds(5)).until(()->jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type='Lock' AND query LIKE '%employee%'",Long.class)>0);
                assertThat(event.isDone()).isFalse();
            }finally{release.countDown();}
            assertThat(chunk.get(10,TimeUnit.SECONDS)).isEqualTo(2);assertThat(event.get(10,TimeUnit.SECONDS).summary().stale()).isEqualTo(1);
        }finally{release.countDown();}
        assertThat(version("A")).isEqualTo(3);assertThat(jdbc.queryForObject("SELECT shift_code FROM employee WHERE tenant_id=? AND external_id='A'",String.class,JwtTestTokens.ACME)).isEqualTo("S2");
    }
}
