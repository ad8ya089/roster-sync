package com.moveinsync.rostersync.infrastructure.persistence;

import java.util.UUID;

public interface OfficeRepositoryWrites {
    OfficeEntity insert(UUID tenantId, OfficeEntity office);
}
