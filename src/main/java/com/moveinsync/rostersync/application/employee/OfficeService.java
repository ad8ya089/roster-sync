package com.moveinsync.rostersync.application.employee;

import com.moveinsync.rostersync.api.error.NotFoundException;
import com.moveinsync.rostersync.infrastructure.persistence.OfficeEntity;
import com.moveinsync.rostersync.infrastructure.persistence.OfficeRepository;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class OfficeService {
    private final OfficeRepository offices;
    private final Clock clock;
    private final com.moveinsync.rostersync.infrastructure.cache.AfterCommitCacheEvictor eviction;
    public OfficeService(OfficeRepository offices, Clock clock,com.moveinsync.rostersync.infrastructure.cache.AfterCommitCacheEvictor eviction) { this.offices = offices; this.clock = clock;this.eviction=eviction; }

    public List<OfficeView> list(UUID tenantId, boolean includeInactive) {
        return offices.findAllByTenantIdOrderByCodeAsc(tenantId).stream()
                .filter(office -> includeInactive || office.isActive()).map(OfficeView::from).toList();
    }
    public OfficeView get(UUID tenantId, String code) { return OfficeView.from(find(tenantId, code)); }

    @Transactional
    public OfficeView create(UUID tenantId, String code, String name) {
        var view=OfficeView.from(offices.insert(tenantId, new OfficeEntity(tenantId, code, name, clock.instant())));eviction.office(tenantId,code);return view;
    }

    @Transactional
    public OfficeView patch(UUID tenantId, String code, String name, Boolean active) {
        var office = find(tenantId, code);
        office.update(name, active);
        eviction.office(tenantId,code);
        return OfficeView.from(office);
    }

    private OfficeEntity find(UUID tenantId, String code) {
        return offices.findByTenantIdAndCode(tenantId, code).orElseThrow(NotFoundException::new);
    }

    public record OfficeView(String code, String name, boolean active) {
        static OfficeView from(OfficeEntity office) { return new OfficeView(office.getCode(), office.getName(), office.isActive()); }
        @Override public String toString() { return "OfficeView[code=" + code + ", active=" + active + "]"; }
    }
}
