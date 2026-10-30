package com.moveinsync.rostersync.infrastructure.persistence;

import com.moveinsync.rostersync.domain.validation.OfficeLookup;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class DbOfficeLookup implements OfficeLookup {
    private final OfficeRepository offices;
    private final org.springframework.cache.Cache cache;
    public DbOfficeLookup(OfficeRepository offices,org.springframework.cache.CacheManager caches) { this.offices = offices;cache=caches.getCache("offices"); }

    @Override @Transactional(readOnly = true)
    public Status lookup(UUID tenantId, String officeCode) {
        var key=new com.moveinsync.rostersync.infrastructure.cache.CacheConfig.OfficeKey(tenantId,officeCode);
        var ref=cache.get(key,com.moveinsync.rostersync.infrastructure.cache.CacheConfig.OfficeRef.class);
        if(ref==null){
            var found=offices.findByTenantIdAndCode(tenantId,officeCode);if(found.isEmpty())return Status.UNKNOWN;
            var office=found.get();ref=new com.moveinsync.rostersync.infrastructure.cache.CacheConfig.OfficeRef(office.getId(),office.getCode(),office.isActive());cache.put(key,ref);
        }
        return ref.active()?Status.ACTIVE:Status.INACTIVE;
    }
}
