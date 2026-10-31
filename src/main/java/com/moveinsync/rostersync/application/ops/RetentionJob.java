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
    private final ReconciliationFileCleanup files;
    private volatile boolean stopping;
    public RetentionJob(RetentionBatch batches,RosterProperties properties,Clock clock,ReconciliationFileCleanup files) {this.batches=batches;this.properties=properties;this.clock=clock;this.files=files;}
    @Scheduled(cron="${roster.retention.cron}")
    public void run() {
        var now=clock.instant();var retention=properties.retention();
        remove(RetentionBatch.Data.RECEIPTS,now.minus(retention.receipts()));
        remove(RetentionBatch.Data.OUTBOX,now.minus(retention.outbox()));
        remove(RetentionBatch.Data.PROCESSED,now.minus(retention.processedEvents()));
        remove(RetentionBatch.Data.RECON_ACTIONS,now.minus(retention.reconActions()));
        while(!stopping && files.expiredUploads(now.minus(retention.uploads()))==500) { /* one short tx per batch */ }
        if(!stopping) files.abandoned();
    }
    private void remove(RetentionBatch.Data data,Instant cutoff) {while(!stopping && batches.delete(data,cutoff)==5000) { /* separate short tx per batch */ }}
    @EventListener(ContextClosedEvent.class) public void stop() {stopping=true;}
}
