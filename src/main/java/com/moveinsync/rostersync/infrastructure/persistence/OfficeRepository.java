package com.moveinsync.rostersync.infrastructure.persistence;

import java.util.*;
import org.springframework.data.repository.Repository;

public interface OfficeRepository extends Repository<OfficeEntity, Long>, OfficeRepositoryWrites {
    List<OfficeEntity> findAllByTenantIdOrderByCodeAsc(UUID tenantId);
    Optional<OfficeEntity> findByTenantIdAndCode(UUID tenantId, String code);
    List<OfficeEntity> findAllByTenantIdAndCodeIn(UUID tenantId,Collection<String> codes);
}
