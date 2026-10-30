package com.moveinsync.rostersync.application.recon;
import java.util.UUID;
/** Boundary between planning and resumable transactional application. */
public interface ReconApplyWork {void resume(UUID tenantId,UUID runId);}
