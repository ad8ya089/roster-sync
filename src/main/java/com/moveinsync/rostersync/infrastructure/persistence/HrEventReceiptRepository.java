package com.moveinsync.rostersync.infrastructure.persistence;
import java.util.UUID;
import org.springframework.data.domain.*;
import org.springframework.data.repository.Repository;

public interface HrEventReceiptRepository extends Repository<HrEventReceiptEntity, Long> {
    Page<HrEventReceiptEntity> findByTenantIdAndExternalIdOrderByVersionDesc(UUID tenantId, String externalId, Pageable pageable);
}
