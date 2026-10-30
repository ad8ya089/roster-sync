package com.moveinsync.rostersync.application.outbox;
import com.moveinsync.rostersync.config.RosterProperties;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.*;
import org.springframework.context.event.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OutboxPublisher {
    private static final Logger LOG=LoggerFactory.getLogger(OutboxPublisher.class);
    private final OutboxRowProcessor rows;
    private final RosterProperties properties;
    private final AtomicBoolean stopping=new AtomicBoolean();
    private final AtomicBoolean ticking=new AtomicBoolean();
    public OutboxPublisher(OutboxRowProcessor rows,RosterProperties properties) {this.rows=rows; this.properties=properties;}
    @Scheduled(fixedDelayString="${roster.outbox.poll-interval}")
    public void poll() {if(properties.outbox().enabled()) tick();}
    public void tick() {
        if(stopping.get() || !ticking.compareAndSet(false,true)) return;
        try {
            // No batch transaction: a DB lock spans only one event dispatch, never multiple rows/calls.
            for(int index=0;index<properties.outbox().batchSize() && !stopping.get();index++) if(!rows.processNext()) break;
        } catch(RuntimeException failure) {LOG.error("outbox.tick.failed exceptionClass={}",failure.getClass().getSimpleName());}
        finally {ticking.set(false);}
    }
    @EventListener(ContextClosedEvent.class)
    public void stopClaiming() {stopping.set(true);}
}
