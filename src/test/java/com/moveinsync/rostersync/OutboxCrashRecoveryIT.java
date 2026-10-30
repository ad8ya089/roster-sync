package com.moveinsync.rostersync;

import com.moveinsync.rostersync.application.ingest.EventIngestionService;
import com.moveinsync.rostersync.application.outbox.OutboxRowProcessor;
import com.moveinsync.rostersync.infrastructure.fault.*;
import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import static org.assertj.core.api.Assertions.*;

@Timeout(30)
class OutboxCrashRecoveryIT extends AbstractOutboxIntegrationTest {
    @Test void crashAfterCommitBeforePublish() {
        try(var first=open()) {
            var result=first.getBean(EventIngestionService.class).ingest(JwtTestTokens.ACME,List.of(event("CRASH1",1,"CREATE","BLR-1","S1")));
            assertThat(result.summary().applied()).isEqualTo(1);
            first.getBean(FaultInjector.class).arm(FaultPoint.OUTBOX_BEFORE_DISPATCH,FaultMode.THROW,true,0);
            assertThatThrownBy(()->first.getBean(OutboxRowProcessor.class).processNext()).isInstanceOf(SimulatedCrashException.class);
            assertThat(version("CRASH1")).isEqualTo(1);assertThat(count("outbox_event")).isEqualTo(1);
        }
        try(var restarted=open()) {
            assertThat(restarted.getBean(OutboxRowProcessor.class).processNext()).isTrue();
            assertThat(restarted.getBean(RecordingEventSink.class).deliveries()).hasSize(1);
            assertThat(jdbc.queryForObject("SELECT status FROM outbox_event",String.class)).isEqualTo("PUBLISHED");
        }
    }
    @Test void crashInsideApplyRollsBackEmployeeReceiptAndOutbox() {
        try(var first=open()) {
            first.getBean(FaultInjector.class).arm(FaultPoint.APPLY_AFTER_EMPLOYEE_WRITE,FaultMode.THROW,true,0);
            var result=first.getBean(EventIngestionService.class).ingest(JwtTestTokens.ACME,List.of(event("CRASH2",1,"CREATE","BLR-1","S1")));
            assertThat(result.summary().retryableError()).isEqualTo(1);
        }
        try(var restarted=open()) {
            assertThat(restarted.getBean(OutboxRowProcessor.class).processNext()).isFalse();
            assertThat(count("hr_event_receipt")).isZero();assertThat(count("outbox_event")).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM employee WHERE external_id='CRASH2'",Long.class)).isZero();
        }
    }
    @Test void crashAfterConsumerCommitRedeliversWithoutRepeatingTheEffect() {
        String bookingsAfter;
        try(var first=open()) {
            first.getBean(EventIngestionService.class).ingest(JwtTestTokens.ACME,List.of(event("E1002",2,"DEACTIVATE",null,null)));
            first.getBean(FaultInjector.class).arm(FaultPoint.OUTBOX_BEFORE_MARK_PUBLISHED,FaultMode.THROW,true,0);
            assertThatThrownBy(()->first.getBean(OutboxRowProcessor.class).processNext()).isInstanceOf(SimulatedCrashException.class);
            assertThat(first.getBean(RecordingEventSink.class).deliveries()).hasSize(1);
            assertThat(count("processed_event")).isEqualTo(1);
            bookingsAfter=jdbc.queryForObject("SELECT json_agg(cab_booking ORDER BY id)::text FROM cab_booking",String.class);
        }
        try(var restarted=open()) {
            assertThat(restarted.getBean(OutboxRowProcessor.class).processNext()).isTrue();
            assertThat(restarted.getBean(RecordingEventSink.class).deliveries()).hasSize(1);
            assertThat(count("processed_event")).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT json_agg(cab_booking ORDER BY id)::text FROM cab_booking",String.class)).isEqualTo(bookingsAfter);
            assertThat(jdbc.queryForObject("SELECT status FROM outbox_event",String.class)).isEqualTo("PUBLISHED");
        }
    }
    private ConfigurableApplicationContext open() {
        // These are real, separately closed web/application contexts, never Spring's test cache.
        // Only the Testcontainer database persists across the two application lifetimes.
        return new SpringApplicationBuilder(RosterSyncApplication.class,OutboxTestConfiguration.class).profiles("test","demo").run(
                "--server.port=0","--spring.datasource.url="+POSTGRES.getJdbcUrl(),"--spring.datasource.username="+POSTGRES.getUsername(),
                "--spring.datasource.password="+POSTGRES.getPassword(),"--spring.datasource.hikari.maximum-pool-size=4",
                "--roster.demo.password=test-demo-password","--roster.outbox.enabled=false","--spring.main.banner-mode=off");
    }
}
