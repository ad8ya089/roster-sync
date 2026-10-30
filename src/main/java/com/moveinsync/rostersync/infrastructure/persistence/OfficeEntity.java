package com.moveinsync.rostersync.infrastructure.persistence;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "office")
@Access(AccessType.FIELD)
public class OfficeEntity extends TenantScopedEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, length = 32) private String code;
    @Column(nullable = false, length = 200) private String name;
    @Column(nullable = false) private boolean active;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;

    protected OfficeEntity() { }
    public OfficeEntity(UUID tenantId, String code, String name, Instant createdAt) {
        setTenantId(tenantId);
        this.code = code;
        this.name = name;
        this.active = true;
        this.createdAt = createdAt;
    }
    public Long getId() { return id; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public boolean isActive() { return active; }
    public void update(String name, Boolean active) {
        if (name != null) this.name = name;
        if (active != null) this.active = active;
    }
}
