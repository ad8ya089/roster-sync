package com.moveinsync.rostersync.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import java.time.Instant;

@MappedSuperclass
public abstract class AuditedTenantEntity extends TenantScopedEntity {
    private Instant createdAt;
    private Instant updatedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    public Instant getCreatedAt() { return createdAt; }
    protected void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    @Column(name = "updated_at", nullable = false)
    public Instant getUpdatedAt() { return updatedAt; }
    protected void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
