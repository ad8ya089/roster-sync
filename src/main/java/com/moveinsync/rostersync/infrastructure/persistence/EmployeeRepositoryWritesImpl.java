package com.moveinsync.rostersync.infrastructure.persistence;
import jakarta.persistence.EntityManager;
import java.util.UUID;

public class EmployeeRepositoryWritesImpl implements EmployeeRepositoryWrites {
    private final EntityManager entities;
    public EmployeeRepositoryWritesImpl(EntityManager entities) { this.entities = entities; }
    public EmployeeEntity saveAndFlush(UUID tenantId, EmployeeEntity employee) {
        persist(tenantId,employee);entities.flush();return employee;
    }
    public EmployeeEntity persist(UUID tenantId,EmployeeEntity employee){
        if (!tenantId.equals(employee.getTenantId())) throw new IllegalArgumentException("Employee tenant mismatch");
        // IDENTITY inserts immediately; recon defers dirty updates to the chunk flush.
        entities.persist(employee);return employee;
    }
    public void flush(UUID tenantId) { java.util.Objects.requireNonNull(tenantId); entities.flush(); }
}
