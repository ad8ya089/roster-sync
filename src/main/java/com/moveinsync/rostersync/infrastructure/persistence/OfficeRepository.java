package com.moveinsync.rostersync.infrastructure.persistence;

import java.util.*;
import org.springframework.data.repository.Repository;

public interface OfficeRepository extends Repository<OfficeEntity, Long>, OfficeRepositoryWrites {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_READ)
    @org.springframework.data.jpa.repository.Query("select office from OfficeEntity office where office.tenantId=:tenantId and office.code=:code")
    Optional<OfficeEntity> findForShare(UUID tenantId, String code);
    List<OfficeEntity> findAllByTenantIdOrderByCodeAsc(UUID tenantId);
    Optional<OfficeEntity> findByTenantIdAndCode(UUID tenantId, String code);
}
