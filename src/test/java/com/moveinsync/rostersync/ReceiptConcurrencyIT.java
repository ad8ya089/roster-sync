package com.moveinsync.rostersync;
import com.moveinsync.rostersync.infrastructure.fault.*;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

@Timeout(30)
class ReceiptConcurrencyIT extends AbstractIngestionIntegrationTest {
    @MockitoBean FaultInjector faults;
    @Test void waitingSameKeyInsertSeesDuplicateAfterFirstCommit() throws Exception { verifyWaitingInsert(false); }
    @Test void waitingSameKeyInsertSucceedsAfterFirstRollback() throws Exception { verifyWaitingInsert(true); }

    private void verifyWaitingInsert(boolean rollback) throws Exception {
        var firstWritten=new CountDownLatch(1); var release=new CountDownLatch(1); var hits=new AtomicInteger();
        doAnswer(call->{
            if(hits.incrementAndGet()==1) {
                firstWritten.countDown(); if(!release.await(5,TimeUnit.SECONDS)) throw new AssertionError("Commit gate timed out");
                if(rollback) throw new SimulatedCrashException();
            }
            return null;
        }).when(faults).hit(FaultPoint.APPLY_AFTER_EMPLOYEE_WRITE);
        var record=event("KEY1",7,"CREATE","BLR-1","S1");
        try(var executor=Executors.newFixedThreadPool(2)) {
            var first=executor.submit(()->ingestion.ingest(JwtTestTokens.ACME,List.of(record)));
            assertThat(firstWritten.await(5,TimeUnit.SECONDS)).isTrue();
            var second=executor.submit(()->ingestion.ingest(JwtTestTokens.ACME,List.of(record)));
            try {
                await().atMost(Duration.ofSeconds(3)).untilAsserted(()->assertThat(jdbc.queryForObject("""
                        SELECT count(*) FROM pg_stat_activity WHERE datname=current_database()
                        AND wait_event_type='Lock' AND query ILIKE '%INSERT INTO hr_event_receipt%'
                        """,Integer.class)).isGreaterThan(0));
                assertThat(second.isDone()).isFalse(); assertThat(count("hr_event_receipt")).isZero();
            } finally { release.countDown(); }
            var firstResult=first.get(10,TimeUnit.SECONDS); var secondResult=second.get(10,TimeUnit.SECONDS);
            assertThat(firstResult.summary().retryableError()).isEqualTo(rollback ? 1 : 0);
            assertThat(secondResult.summary().applied()).isEqualTo(rollback ? 1 : 0);
            assertThat(secondResult.summary().duplicate()).isEqualTo(rollback ? 0 : 1);
            assertThat(employeeOutbox()).isEqualTo(1); assertThat(count("hr_event_receipt")).isEqualTo(1); assertThat(version("KEY1")).isEqualTo(7);
        } finally { release.countDown(); }
    }
}
