package com.moveinsync.rostersync;
import com.moveinsync.rostersync.application.ingest.IngestResult;
import com.moveinsync.rostersync.infrastructure.persistence.*;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Timeout(30)
class ConcurrentEventsIT extends AbstractIngestionIntegrationTest {
    @MockitoSpyBean EmployeeRepositoryWritesImpl writes;
    @Autowired MeterRegistry metrics;

    @Test void sameEmployeeRandomOrder() throws Exception {
        var versions=new ArrayList<>(java.util.stream.IntStream.rangeClosed(1,200).boxed().toList());
        Collections.shuffle(versions,new Random(20261030));
        var records=versions.stream().map(version->event("CORDER",version,"UPDATE","BLR-1","S"+version)).toList();
        var results=run(records,16);
        assertThat(version("CORDER")).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT shift_code FROM employee WHERE tenant_id=? AND external_id='CORDER'",String.class,JwtTestTokens.ACME)).isEqualTo("S200");
        assertThat(employeeOutbox()).isEqualTo(results.stream().mapToLong(result->result.summary().applied()).sum());
        assertThat(count("hr_event_receipt")).isEqualTo(200);
        assertThat(results).allSatisfy(result->assertThat(result.summary().retryableError()).isZero());
    }
    @Test void sameEventManyTimes() throws Exception {
        var results=run(Collections.nCopies(20,event("CSAME",1,"CREATE","BLR-1","S1")),20);
        assertThat(results.stream().mapToLong(result->result.summary().applied()).sum()).isEqualTo(1);
        assertThat(results.stream().mapToLong(result->result.summary().duplicate()).sum()).isEqualTo(19);
        assertThat(employeeOutbox()).isEqualTo(1); assertThat(count("hr_event_receipt")).isEqualTo(1);
    }
    @Test void concurrentCreateRace() throws Exception {
        var creatorsReady=new CountDownLatch(2);
        doAnswer(call->{
            EmployeeEntity entity=call.getArgument(1);
            if (entity.getExternalId().equals("CCREATE")) {
                creatorsReady.countDown(); assertThat(creatorsReady.await(5,TimeUnit.SECONDS)).isTrue();
            }
            return call.callRealMethod();
        }).when(writes).saveAndFlush(any(UUID.class),any(EmployeeEntity.class));
        double retries=metrics.counter("roster.apply.retries","reason","unique_race").count();
        var records=java.util.stream.IntStream.rangeClosed(1,10).mapToObj(version->event("CCREATE",version,"CREATE","BLR-1","S"+version)).toList();
        var results=run(records,10);
        assertThat(version("CCREATE")).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM employee WHERE tenant_id=? AND external_id='CCREATE'",Long.class,JwtTestTokens.ACME)).isEqualTo(1);
        assertThat(metrics.counter("roster.apply.retries","reason","unique_race").count()).isGreaterThan(retries);
        assertThat(employeeOutbox()).isEqualTo(results.stream().mapToLong(result->result.summary().applied()).sum());
        assertThat(count("hr_event_receipt")).isEqualTo(10);
    }
    private List<IngestResult> run(List<Map<String,Object>> records,int threads) throws Exception {
        var start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(threads)) {
            var futures=new ArrayList<Future<IngestResult>>();
            for(var record:records) futures.add(executor.submit(()->{
                if(!start.await(5,TimeUnit.SECONDS)) throw new AssertionError("Start gate timed out");
                return ingestion.ingest(JwtTestTokens.ACME,List.of(record));
            }));
            start.countDown();
            var results=new ArrayList<IngestResult>();
            for(var future:futures) results.add(future.get(20,TimeUnit.SECONDS));
            return results;
        } finally { start.countDown(); }
    }
}
