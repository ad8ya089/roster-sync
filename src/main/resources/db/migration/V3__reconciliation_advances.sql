ALTER TABLE recon_action DROP CONSTRAINT recon_action_action_check;
ALTER TABLE recon_action ADD CONSTRAINT recon_action_action_check
    CHECK (action IN ('CREATE','UPDATE','HEAL','ADVANCE','DEACTIVATE'));
ALTER TABLE recon_run ADD COLUMN planned_advances INTEGER NOT NULL DEFAULT 0;
ALTER TABLE recon_run ADD COLUMN heartbeat_at TIMESTAMPTZ;
CREATE INDEX ix_recon_action_external_pending ON recon_action (tenant_id,run_id,external_id) WHERE status='PENDING';
