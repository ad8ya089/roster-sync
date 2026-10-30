package com.moveinsync.rostersync.infrastructure.persistence;

import java.util.UUID;

public interface OfficeRepositoryWrites {
    OfficeEntity refreshPlain(UUID tenantId, OfficeEntity office);
    OfficeEntity insert(UUID tenantId, OfficeEntity office);
}
