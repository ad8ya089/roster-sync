package com.moveinsync.rostersync.infrastructure.persistence;

import com.moveinsync.rostersync.domain.model.EmploymentStatus;
import jakarta.persistence.LockModeType;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.Repository;

public interface EmployeeRepository extends Repository<EmployeeEntity, Long>, EmployeeRepositoryWrites {
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select employee from EmployeeEntity employee where employee.tenantId=:tenantId and employee.externalId=:externalId")
    Optional<EmployeeEntity> findForShare(UUID tenantId, String externalId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select employee from EmployeeEntity employee where employee.tenantId=:tenantId and employee.externalId=:externalId")
    Optional<EmployeeEntity> findForUpdate(UUID tenantId, String externalId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select employee from EmployeeEntity employee where employee.tenantId=:tenantId and employee.externalId in :externalIds order by employee.externalId")
    List<EmployeeEntity> findAllForUpdate(UUID tenantId,Collection<String> externalIds);

    @EntityGraph(attributePaths = "office")
    Optional<EmployeeEntity> findByTenantIdAndExternalId(UUID tenantId, String externalId);

    @EntityGraph(attributePaths = "office")
    @Query("""
            select employee from EmployeeEntity employee left join employee.office office
            where employee.tenantId=:tenantId and (:status is null or employee.status=:status)
            and (:officeCode is null or office.code=:officeCode)
            """)
    Page<EmployeeEntity> findPage(UUID tenantId, EmploymentStatus status, String officeCode, Pageable pageable);
}
