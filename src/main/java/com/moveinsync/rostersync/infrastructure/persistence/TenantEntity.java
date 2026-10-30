package com.moveinsync.rostersync.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "tenant")
public class TenantEntity {
    @Id private UUID id;
    @Column(nullable = false, length = 32) private String code;
    @Column(nullable = false, length = 200) private String name;
    @Column(name = "created_at", nullable = false) private Instant createdAt;

    protected TenantEntity() { }
    public UUID getId() { return id; }
    public String getCode() { return code; }
}
