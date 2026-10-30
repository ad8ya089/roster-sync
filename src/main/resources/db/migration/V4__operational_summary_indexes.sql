CREATE INDEX ix_receipt_tenant_received ON hr_event_receipt (tenant_id, received_at);
CREATE INDEX ix_rejection_tenant_created ON record_rejection (tenant_id, created_at);
CREATE INDEX ix_outbox_tenant_status ON outbox_event (tenant_id, status);
