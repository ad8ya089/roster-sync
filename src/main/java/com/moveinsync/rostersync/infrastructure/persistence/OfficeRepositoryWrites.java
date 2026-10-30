package com.moveinsync.rostersync.infrastructure.persistence;

import java.util.UUID;

public interface OfficeRepositoryWrites {
    OfficeEntity refreshForShare(UUID tenantId, OfficeEntity office);
    OfficeEntity insert(UUID tenantId, OfficeEntity office);
}
