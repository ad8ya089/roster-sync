package com.moveinsync.rostersync;

import com.moveinsync.rostersync.application.ops.OutboxOpsService;
import java.time.Duration;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@Timeout(30)
class OutboxRetryIT extends AbstractOutboxIntegrationTest {
    @Autowired OutboxOpsService ops;
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("sinkExecutor") ExecutorService executor;
    @Test void aFullBoundedExecutorCountsAsAPublishFailure() throws Exception {
        long id=pending("QUEUE","EmployeeUpdated");var release=new CountDownLatch(1);var started=new CountDownLatch(4);
        var tasks=new java.util.ArrayList<CompletableFuture<Void>>();
        try {
            for(int index=0;index<104;index++) tasks.add(CompletableFuture.runAsync(()->{
                started.countDown();try {if(!release.await(5,TimeUnit.SECONDS)) throw new IllegalStateException();}
                catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException();}
            },executor));
            assertThat(started.await(2,TimeUnit.SECONDS)).isTrue();
            publisher.tick();assertAttempt(id,1,"RejectedExecutionException",1);assertThat(status(id)).isEqualTo("PENDING");
        } finally {release.countDown();CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new)).get(3,TimeUnit.SECONDS);}
    }
    @Test void exceptionsBackOffDieOnceAndManualRetryRevives() {
        long id=pending("R1","EmployeeUpdated");
        sink.action(event->{throw new IllegalArgumentException("PII_CANARY_never_store_this");});
        publisher.tick();assertThat(status(id)).isEqualTo("PENDING");assertAttempt(id,1,"IllegalArgumentException",1);
        // A delayed row does not block a later ready row.
        long later=pending("R2","EmployeeCreated");sink.action(event->{if(event.aggregateId().equals("R1")) throw new IllegalArgumentException();});
        publisher.tick();assertThat(status(later)).isEqualTo("PUBLISHED");
        clock.advance(Duration.ofSeconds(1));publisher.tick();assertAttempt(id,2,"IllegalArgumentException",2);
        clock.advance(Duration.ofSeconds(2));publisher.tick();assertThat(status(id)).isEqualTo("DEAD");
        assertThat(count("ops_alert")).isEqualTo(1);publisher.tick();assertThat(count("ops_alert")).isEqualTo(1);
        assertThatThrownBy(()->ops.retry(JwtTestTokens.ACME,later)).hasMessage("Invalid outbox state");
        ops.retry(JwtTestTokens.ACME,id);assertThat(status(id)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT attempts FROM outbox_event WHERE id=?",Integer.class,id)).isZero();
        sink.action(event->{ });publisher.tick();assertThat(status(id)).isEqualTo("PUBLISHED");
        assertThat(count("ops_alert")).isEqualTo(1);
    }
    @Test void timeoutDoesNotCancelTheSinkAndRedeliveryIsPossible() throws Exception {
        long id=pending("SLOW","EmployeeUpdated");var release=new CountDownLatch(1);var entered=new CountDownLatch(1);
        sink.action(event->{entered.countDown();try {if(!release.await(5,TimeUnit.SECONDS)) throw new IllegalStateException();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException();}});
        try {
            publisher.tick();assertThat(entered.getCount()).isZero();assertAttempt(id,1,"TimeoutException",1);
            assertThat(sink.deliveries()).isEmpty();
        } finally {release.countDown();}
        await().atMost(Duration.ofSeconds(3)).untilAsserted(()->assertThat(sink.deliveries()).hasSize(1));
        clock.advance(Duration.ofSeconds(1));publisher.tick();assertThat(status(id)).isEqualTo("PUBLISHED");
        assertThat(sink.deliveries().values()).containsExactly(2);
    }
    private void assertAttempt(long id,int count,String error,int seconds) {
        var row=jdbc.queryForMap("SELECT attempts,last_error,next_attempt_at FROM outbox_event WHERE id=?",id);
        assertThat(row.get("attempts")).isEqualTo(count);assertThat(row.get("last_error")).isEqualTo(error);
        assertThat(((java.sql.Timestamp)row.get("next_attempt_at")).toInstant()).isEqualTo(clock.instant().plusSeconds(seconds));
    }
}
