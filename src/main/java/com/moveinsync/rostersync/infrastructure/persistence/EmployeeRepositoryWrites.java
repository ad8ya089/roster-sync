package com.moveinsync.rostersync.infrastructure.persistence;
import java.util.UUID;

public interface EmployeeRepositoryWrites {
    EmployeeEntity saveAndFlush(UUID tenantId, EmployeeEntity employee);
    EmployeeEntity persist(UUID tenantId,EmployeeEntity employee);
    void flush(UUID tenantId);
}
