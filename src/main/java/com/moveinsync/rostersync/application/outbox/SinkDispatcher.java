package com.moveinsync.rostersync.application.outbox;
import com.moveinsync.rostersync.config.RosterProperties;
import java.util.*;
import java.util.concurrent.*;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class SinkDispatcher {
    private final List<EventSink> sinks;
    private final ExecutorService executor;
    private final long timeoutMillis;
    private final com.moveinsync.rostersync.infrastructure.metrics.RosterMetrics metrics;
    public SinkDispatcher(List<EventSink> sinks,@Qualifier("sinkExecutor") ExecutorService executor,RosterProperties properties,com.moveinsync.rostersync.infrastructure.metrics.RosterMetrics metrics) {
        this.sinks=List.copyOf(sinks); this.executor=executor; timeoutMillis=properties.outbox().sinkTimeout().toMillis();this.metrics=metrics;
    }
    public void dispatch(OutboxEventView event) {
        // Timeout completes the future, not the running sink. Late completion can race redelivery:
        // consumers must deduplicate independently committed work. A full queue is a failure too.
        var active=new java.util.concurrent.atomic.AtomicReference<>("executor");
        try {CompletableFuture.runAsync(()->{
            var previous=MDC.getCopyOfContextMap();
            try {
                MDC.put("tenantId",event.tenantId().toString()); MDC.put("eventId",event.eventId().toString());
                MDC.put(event.eventType().startsWith("Reconciliation")?"runId":"employeeId",event.aggregateId());
                boolean supported=false;
                for(var sink:sinks) if(sink.supports(event.eventType())) {
                    supported=true;active.set(sink.name());if(event.attempts()>0)metrics.sinkRetry(sink.name(),event.eventType());
                    sink.handle(event);metrics.sinkPublished(sink.name(),event.eventType());
                }
                if(!supported) throw new IllegalStateException("No event sink configured");
            } finally {if(previous==null) MDC.clear(); else MDC.setContextMap(previous);}
        },executor).orTimeout(timeoutMillis,TimeUnit.MILLISECONDS).join();
        } catch(RuntimeException failed){Throwable cause=failed instanceof CompletionException && failed.getCause()!=null?failed.getCause():failed;
            metrics.sinkFailed(active.get(),event.eventType());throw new SinkDispatchException(active.get(),cause);}
    }
}
