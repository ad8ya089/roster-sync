package com.moveinsync.rostersync.application.ops;

import com.moveinsync.rostersync.config.RosterProperties;
import java.time.Clock;
import java.time.Instant;
import org.springframework.context.event.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class RetentionJob {
    private final RetentionBatch batches; private final RosterProperties properties; private final Clock clock;
    private volatile boolean stopping;
    public RetentionJob(RetentionBatch batches,RosterProperties properties,Clock clock) {this.batches=batches;this.properties=properties;this.clock=clock;}
    @Scheduled(cron="${roster.retention.cron}")
    public void run() {
        var now=clock.instant();var retention=properties.retention();
        remove(RetentionBatch.Data.RECEIPTS,now.minus(retention.receipts()));
        remove(RetentionBatch.Data.OUTBOX,now.minus(retention.outbox()));
        remove(RetentionBatch.Data.PROCESSED,now.minus(retention.processedEvents()));
    }
    private void remove(RetentionBatch.Data data,Instant cutoff) {while(!stopping && batches.delete(data,cutoff)==5000) { /* separate short tx per batch */ }}
    @EventListener(ContextClosedEvent.class) public void stop() {stopping=true;}
}
