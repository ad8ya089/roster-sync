package com.moveinsync.rostersync.application.recon;
import com.moveinsync.rostersync.infrastructure.persistence.ReconRunDao;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.*;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ReconciliationRecovery {
    private static final Logger LOG=LoggerFactory.getLogger(ReconciliationRecovery.class);
    private final ReconRunDao runs;private final ReconWorkQueue queue;private final ReconciliationRunner runner;private final Clock clock;
    private final com.moveinsync.rostersync.application.ops.ReconciliationFileCleanup files;
    public ReconciliationRecovery(ReconRunDao runs,ReconWorkQueue queue,ReconciliationRunner runner,Clock clock,com.moveinsync.rostersync.application.ops.ReconciliationFileCleanup files){this.runs=runs;this.queue=queue;this.runner=runner;this.clock=clock;this.files=files;}
    @EventListener(ApplicationReadyEvent.class)
    public void startup(){recover(true);files.abandoned();}
    @Scheduled(fixedDelayString="${roster.reconciliation.recovery-interval}",initialDelayString="${roster.reconciliation.recovery-interval}")
    public void watchdog(){recover(false);}
    private void recover(boolean startup) {
        // At startup no old worker survives (single instance). During normal operation a slow
        // live run must not be failed: require a stale heartbeat AND no local queued/running worker.
        // Multiple instances would require a durable lease, not this in-memory ownership check.
        for(var run:runs.recoverable(startup?null:clock.instant().minus(Duration.ofMinutes(2)))) {
            if(queue.active(run.id())) continue;
            try {
                if(run.status().equals("APPLYING")) queue.submit(run.id(),()->runner.run(run.tenantId(),run.id()));
                else runs.fail(run.tenantId(),run.id(),run.status(),"INTERRUPTED");
            }catch(RuntimeException raced){LOG.warn("recon.recovery.failed runId={} exceptionClass={}",run.id(),raced.getClass().getSimpleName());}
        }
    }
}
