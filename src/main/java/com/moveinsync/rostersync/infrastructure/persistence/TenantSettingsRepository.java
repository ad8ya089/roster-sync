package com.moveinsync.rostersync.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.Repository;

public interface TenantSettingsRepository extends Repository<TenantSettingsEntity, UUID> {
    Optional<TenantSettingsEntity> findByTenantId(UUID tenantId);
}
