package com.moveinsync.rostersync.infrastructure.persistence;

import jakarta.persistence.EntityManager;
import java.util.UUID;

public class OfficeRepositoryWritesImpl implements OfficeRepositoryWrites {
    private final EntityManager entities;
    public OfficeRepositoryWritesImpl(EntityManager entities) { this.entities = entities; }

    @Override
    public OfficeEntity refreshPlain(UUID tenantId, OfficeEntity office) {
        if (!tenantId.equals(office.getTenantId())) throw new IllegalArgumentException("Office tenant mismatch");
        // A snapshot may already have loaded this office into JPA's first-level cache. Re-querying
        // a managed entity need not refresh its fields. Plain refresh uses LockModeType.NONE at READ COMMITTED.
        entities.refresh(office);
        return office;
    }

    @Override
    public OfficeEntity insert(UUID tenantId, OfficeEntity office) {
        if (!tenantId.equals(office.getTenantId())) throw new IllegalArgumentException("Office tenant mismatch");
        // A tenant-aware fragment avoids exposing JpaRepository's unscoped findById/save/delete methods.
        entities.persist(office);
        entities.flush();
        return office;
    }
}
