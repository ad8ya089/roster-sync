package com.moveinsync.rostersync.infrastructure.persistence;

import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import java.util.UUID;

@MappedSuperclass
@Access(AccessType.PROPERTY)
public abstract class TenantScopedEntity {
    private UUID tenantId;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    public UUID getTenantId() { return tenantId; }
    protected void setTenantId(UUID tenantId) { this.tenantId = tenantId; }
}
