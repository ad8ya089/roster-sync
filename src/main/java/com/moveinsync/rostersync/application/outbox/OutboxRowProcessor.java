package com.moveinsync.rostersync.application.outbox;
import com.moveinsync.rostersync.domain.outbox.BackoffPolicy;
import com.moveinsync.rostersync.infrastructure.fault.*;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import org.slf4j.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class OutboxRowProcessor {
    private static final Logger LOG=LoggerFactory.getLogger(OutboxRowProcessor.class);
    private final JdbcClient jdbc;
    private final SinkDispatcher dispatcher;
    private final BackoffPolicy backoff;
    private final FaultInjector faults;
    private final Clock clock;
    public OutboxRowProcessor(JdbcClient jdbc,SinkDispatcher dispatcher,BackoffPolicy backoff,FaultInjector faults,Clock clock) {
        this.jdbc=jdbc; this.dispatcher=dispatcher; this.backoff=backoff; this.faults=faults; this.clock=clock;
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public boolean processNext() {
        // Global background queue claim; every subsequent mutation is scoped by the row's tenant.
        var claimed=jdbc.sql("""
                SELECT id,event_id,tenant_id,aggregate_id,aggregate_version,event_type,payload::text,attempts
                FROM outbox_event WHERE status='PENDING' AND next_attempt_at<=?
                ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED
                """).param(Timestamp.from(clock.instant())).query((row,number)->new OutboxEventView(row.getLong(1),row.getObject(2,UUID.class),
                        row.getObject(3,UUID.class),row.getString(4),row.getObject(5,Long.class),row.getString(6),row.getString(7),row.getInt(8))).optional();
        if(claimed.isEmpty()) return false;
        var event=claimed.get();
        var previous=MDC.getCopyOfContextMap();
        try {MDC.put("tenantId",event.tenantId().toString());MDC.put("eventId",event.eventId().toString());
            MDC.put(event.eventType().startsWith("Reconciliation")?"runId":"employeeId",event.aggregateId());return dispatch(event);
        }finally{if(previous==null)MDC.clear();else MDC.setContextMap(previous);}
    }
    private boolean dispatch(OutboxEventView event) {
        faults.hit(FaultPoint.OUTBOX_BEFORE_DISPATCH);
        try { dispatcher.dispatch(event); }
        catch(RuntimeException failed) {
            Throwable cause=failed;
            while((cause instanceof SinkDispatchException || cause instanceof CompletionException || cause instanceof ExecutionException) && cause.getCause()!=null) cause=cause.getCause();
            int attempts=event.attempts()+1; boolean dead=backoff.dead(attempts); var now=clock.instant();
            jdbc.sql("UPDATE outbox_event SET attempts=?,status=?,next_attempt_at=?,last_error=? WHERE tenant_id=? AND id=?")
                    .params(attempts,dead?"DEAD":"PENDING",Timestamp.from(now.plus(backoff.delay(attempts))),cause.getClass().getSimpleName(),event.tenantId(),event.id()).update();
            if(dead) {
                jdbc.sql("""
                        INSERT INTO ops_alert(tenant_id,alert_type,reference_id,message,created_at)
                        SELECT ?,'OUTBOX_EVENT_DEAD',?,?,? WHERE NOT EXISTS
                        (SELECT 1 FROM ops_alert WHERE tenant_id=? AND alert_type='OUTBOX_EVENT_DEAD' AND reference_id=?)
                        """).params(event.tenantId(),event.eventId().toString(),"Outbox event exhausted retries: "+event.eventId(),Timestamp.from(now),
                                event.tenantId(),event.eventId().toString()).update();
            }
            LOG.warn("outbox.failed eventId={} attempts={} status={} errorClass={}",event.eventId(),attempts,dead?"DEAD":"PENDING",cause.getClass().getSimpleName());
            return true;
        }
        // Faults model process death, so let them escape and roll back this row's publisher transaction.
        faults.hit(FaultPoint.OUTBOX_BEFORE_MARK_PUBLISHED);
        jdbc.sql("UPDATE outbox_event SET status='PUBLISHED',published_at=?,last_error=NULL WHERE tenant_id=? AND id=?")
                .params(Timestamp.from(clock.instant()),event.tenantId(),event.id()).update();
        return true;
    }
}
