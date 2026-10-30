package com.moveinsync.rostersync;

import com.moveinsync.rostersync.application.outbox.*;
import com.moveinsync.rostersync.infrastructure.fault.FaultInjector;
import java.sql.Timestamp;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

@Import(OutboxTestConfiguration.class)
@TestPropertySource(properties={"roster.outbox.max-attempts=3","roster.outbox.sink-timeout=150ms","roster.outbox.batch-size=100"})
public abstract class AbstractOutboxIntegrationTest extends AbstractIngestionIntegrationTest {
    @Autowired protected OutboxPublisher publisher;
    @Autowired protected OutboxRowProcessor processor;
    @Autowired protected RecordingEventSink sink;
    @Autowired protected OutboxTestConfiguration.MutableClock clock;
    @Autowired protected FaultInjector faults;
    @BeforeEach void resetPublisher() {clock.reset();sink.reset();faults.clear();}
    protected long pending(String employeeId,String type) {
        return jdbc.queryForObject("""
                INSERT INTO outbox_event(event_id,tenant_id,aggregate_type,aggregate_id,aggregate_version,event_type,payload,next_attempt_at,created_at)
                VALUES (?,?,'Employee',?,1,?,CAST('{}' AS jsonb),?,?) RETURNING id
                """,Long.class,UUID.randomUUID(),JwtTestTokens.ACME,employeeId,type,Timestamp.from(clock.instant()),Timestamp.from(clock.instant()));
    }
    protected String status(long id) {return jdbc.queryForObject("SELECT status FROM outbox_event WHERE id=?",String.class,id);}
}
