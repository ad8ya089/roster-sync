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
    public SinkDispatcher(List<EventSink> sinks,@Qualifier("sinkExecutor") ExecutorService executor,RosterProperties properties) {
        this.sinks=List.copyOf(sinks); this.executor=executor; timeoutMillis=properties.outbox().sinkTimeout().toMillis();
    }
    public void dispatch(OutboxEventView event) {
        // Timeout completes the future, not the running sink. Late completion can race redelivery:
        // consumers must deduplicate independently committed work. A full queue is a failure too.
        CompletableFuture.runAsync(()->{
            var previous=MDC.getCopyOfContextMap();
            try {
                MDC.put("tenantId",event.tenantId().toString()); MDC.put("eventId",event.eventId().toString());
                boolean supported=false;
                for(var sink:sinks) if(sink.supports(event.eventType())) {supported=true; sink.handle(event);}
                if(!supported) throw new IllegalStateException("No event sink configured");
            } finally {if(previous==null) MDC.clear(); else MDC.setContextMap(previous);}
        },executor).orTimeout(timeoutMillis,TimeUnit.MILLISECONDS).join();
    }
}
