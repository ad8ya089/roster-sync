package com.moveinsync.rostersync.api.ops;

import com.moveinsync.rostersync.application.ops.*;
import com.moveinsync.rostersync.application.employee.PageResponse;
import com.moveinsync.rostersync.infrastructure.security.TenantContext;
import jakarta.validation.constraints.*;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/ops")
public class OutboxOpsController {
    private final OutboxOpsService outbox; private final AlertService alerts;
    public OutboxOpsController(OutboxOpsService outbox,AlertService alerts) {this.outbox=outbox;this.alerts=alerts;}
    @GetMapping("/outbox") @PreAuthorize("hasRole('OPERATOR')")
    public PageResponse<OutboxOpsService.OutboxRow> list(@RequestParam(required=false) OutboxOpsService.Status status,
            @RequestParam(defaultValue="0") @Min(0) int page,@RequestParam(defaultValue="20") @Min(1) @Max(200) int size) {
        return outbox.list(TenantContext.currentUser().tenantId(),status,page,size);
    }
    @PostMapping("/outbox/{id}/retry") @ResponseStatus(HttpStatus.NO_CONTENT) @PreAuthorize("hasRole('OPERATOR')")
    public void retry(@PathVariable @Min(1) long id) {outbox.retry(TenantContext.currentUser().tenantId(),id);}
    @GetMapping("/alerts") @PreAuthorize("hasRole('OPERATOR')")
    public List<AlertService.AlertView> alerts(@RequestParam(defaultValue="false") boolean acknowledged) {return alerts.list(TenantContext.currentUser().tenantId(),acknowledged);}
    @PostMapping("/alerts/{id}/ack") @ResponseStatus(HttpStatus.NO_CONTENT) @PreAuthorize("hasRole('OPERATOR')")
    public void ack(@PathVariable @Min(1) long id) {var user=TenantContext.currentUser();alerts.acknowledge(user.tenantId(),id,user.username());}
}
