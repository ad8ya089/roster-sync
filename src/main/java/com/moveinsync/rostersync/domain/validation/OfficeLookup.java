package com.moveinsync.rostersync.domain.validation;

import java.util.UUID;

@FunctionalInterface
public interface OfficeLookup {
    Status lookup(UUID tenantId, String officeCode);
    enum Status { ACTIVE, INACTIVE, UNKNOWN }
}
