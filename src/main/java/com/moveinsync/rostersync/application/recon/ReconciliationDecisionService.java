package com.moveinsync.rostersync.application.recon;
import com.moveinsync.rostersync.infrastructure.persistence.ReconRunDao;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class ReconciliationDecisionService {
    private final ReconRunDao runs;private final ReconWorkQueue queue;private final ReconciliationRunner runner;
    public ReconciliationDecisionService(ReconRunDao runs,ReconWorkQueue queue,ReconciliationRunner runner){this.runs=runs;this.queue=queue;this.runner=runner;}
    public void decide(UUID tenantId,UUID runId,boolean approve,String mode,String username,String note) {
        runs.get(tenantId,runId);runs.decide(tenantId,runId,approve,mode,username,note);
        if(approve) queue.submit(runId,()->runner.run(tenantId,runId));
    }
}
