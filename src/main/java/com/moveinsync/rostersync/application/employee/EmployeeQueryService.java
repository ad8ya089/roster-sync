package com.moveinsync.rostersync.application.employee;
import com.moveinsync.rostersync.api.error.NotFoundException;
import com.moveinsync.rostersync.domain.model.*;
import com.moveinsync.rostersync.infrastructure.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @Transactional(readOnly = true)
public class EmployeeQueryService {
    private final EmployeeRepository employees;
    private final HrEventReceiptRepository receipts;
    private final org.springframework.cache.Cache cache;
    public EmployeeQueryService(EmployeeRepository employees, HrEventReceiptRepository receipts,org.springframework.cache.CacheManager caches) { this.employees=employees; this.receipts=receipts;cache=caches.getCache("employeeView"); }
    public PageResponse<EmployeeView> list(UUID tenantId, EmploymentStatus status, String officeCode, int page, int size) {
        return PageResponse.from(employees.findPage(tenantId, status, officeCode, PageRequest.of(page,size,Sort.by("externalId"))).map(EmployeeView::from));
    }
    public EmployeeView get(UUID tenantId, String employeeId, Role role, String selfId) {
        if (role == Role.EMPLOYEE && !employeeId.equals(selfId)) throw new NotFoundException();
        var key=new com.moveinsync.rostersync.infrastructure.cache.CacheConfig.EmployeeKey(tenantId,employeeId);
        var found=cache.get(key,EmployeeView.class);if(found!=null)return found;
        var view=EmployeeView.from(find(tenantId,employeeId));cache.put(key,view);return view;
    }
    public PageResponse<ReceiptView> receipts(UUID tenantId, String employeeId, int page, int size) {
        find(tenantId, employeeId);
        return PageResponse.from(receipts.findByTenantIdAndExternalIdOrderByVersionDesc(tenantId,employeeId,PageRequest.of(page,size))
                .map(receipt -> new ReceiptView(receipt.getVersion(),receipt.getEventType(),receipt.getOutcome(),receipt.getReceivedAt())));
    }
    private EmployeeEntity find(UUID tenantId, String id) { return employees.findByTenantIdAndExternalId(tenantId,id).orElseThrow(NotFoundException::new); }
    public record ReceiptView(long version, String type, String outcome, Instant receivedAt) { }
}
