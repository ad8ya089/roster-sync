package com.moveinsync.rostersync.application.recon;
import com.moveinsync.rostersync.config.RosterProperties;
import com.moveinsync.rostersync.infrastructure.fault.SimulatedCrashException;
import com.moveinsync.rostersync.infrastructure.persistence.*;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.*;
import org.springframework.stereotype.Service;

@Service
public class ReconciliationApplier implements ReconApplyWork {
    private static final Logger LOG=LoggerFactory.getLogger(ReconciliationApplier.class);
    private final ReconRunDao runs;private final ReconActionDao actions;private final ReconciliationChunkApplier chunks;private final int size;
    private final AtomicLong longestChunkNanos=new AtomicLong();
    public ReconciliationApplier(ReconRunDao runs,ReconActionDao actions,ReconciliationChunkApplier chunks,RosterProperties properties) {
        this.runs=runs;this.actions=actions;this.chunks=chunks;size=properties.reconciliation().chunkSize();
    }
    public void resume(UUID tenantId,UUID runId) {
        String mode=runs.get(tenantId,runId).approvalMode();
        try {
            while(!Thread.currentThread().isInterrupted()) {
                long start=System.nanoTime();int processed;
                try{processed=chunks.applyNext(tenantId,runId,size,mode);}
                catch(SimulatedCrashException interrupted){throw interrupted;}
                catch(RuntimeException chunkFailed) {
                    var remaining=actions.pending(tenantId,runId,size,false);processed=remaining.size();
                    for(var action:remaining) {
                        try{chunks.applyNext(tenantId,runId,1,mode);}
                        catch(SimulatedCrashException interrupted){throw interrupted;}
                        catch(RuntimeException failed){chunks.markFailed(tenantId,runId,action.id(),failed.getClass().getSimpleName());}
                    }
                }
                longestChunkNanos.accumulateAndGet(System.nanoTime()-start,Math::max);
                if(processed==0){runs.transition(tenantId,runId,"APPLYING","COMPLETED");return;}
            }
        } catch(SimulatedCrashException crashed) {LOG.warn("recon.apply.interrupted runId={}",runId);}
    }
    public long longestChunkMillis(){return longestChunkNanos.get()/1000000;}
}
