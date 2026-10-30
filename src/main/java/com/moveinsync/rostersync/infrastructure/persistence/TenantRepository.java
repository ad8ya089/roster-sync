package com.moveinsync.rostersync.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

public interface TenantRepository extends Repository<TenantEntity, UUID> {
    @Query("select tenant from TenantEntity tenant where tenant.id = :tenantId")
    Optional<TenantEntity> findByTenantId(UUID tenantId);
}
