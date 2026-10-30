package com.moveinsync.rostersync.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "tenant_settings")
public class TenantSettingsEntity extends TenantScopedEntity {
    private BigDecimal brakeMaxMissingRatio;
    private Integer brakeMaxMissingCount;
    private Instant updatedAt;
    private String updatedBy;

    protected TenantSettingsEntity() { }
    // This table's tenant key is also its primary key; no extra id or audit columns.
    @Id @Override @Column(name = "tenant_id", nullable = false, updatable = false)
    public UUID getTenantId() { return super.getTenantId(); }
    @Column(name = "brake_max_missing_ratio", precision = 4, scale = 3)
    public BigDecimal getBrakeMaxMissingRatio() { return brakeMaxMissingRatio; }
    protected void setBrakeMaxMissingRatio(BigDecimal value) { brakeMaxMissingRatio = value; }
    @Column(name = "brake_max_missing_count")
    public Integer getBrakeMaxMissingCount() { return brakeMaxMissingCount; }
    protected void setBrakeMaxMissingCount(Integer value) { brakeMaxMissingCount = value; }
    @Column(name = "updated_at", nullable = false)
    public Instant getUpdatedAt() { return updatedAt; }
    protected void setUpdatedAt(Instant value) { updatedAt = value; }
    @Column(name = "updated_by", length = 100)
    public String getUpdatedBy() { return updatedBy; }
    protected void setUpdatedBy(String value) { updatedBy = value; }
}
