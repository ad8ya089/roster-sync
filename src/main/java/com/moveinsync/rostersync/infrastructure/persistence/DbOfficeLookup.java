package com.moveinsync.rostersync.infrastructure.persistence;

import com.moveinsync.rostersync.domain.validation.OfficeLookup;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class DbOfficeLookup implements OfficeLookup {
    private final OfficeRepository offices;
    public DbOfficeLookup(OfficeRepository offices) { this.offices = offices; }

    // Phase 7 can decorate this port with caching without changing any domain validation rules.
    @Override @Transactional(readOnly = true)
    public Status lookup(UUID tenantId, String officeCode) {
        return offices.findByTenantIdAndCode(tenantId, officeCode)
                .map(office -> office.isActive() ? Status.ACTIVE : Status.INACTIVE).orElse(Status.UNKNOWN);
    }
}
