package com.moveinsync.rostersync.api.employee;
import com.moveinsync.rostersync.application.employee.*;
import com.moveinsync.rostersync.domain.model.EmploymentStatus;
import com.moveinsync.rostersync.infrastructure.security.TenantContext;
import jakarta.validation.constraints.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1")
public class EmployeeController {
    private final EmployeeQueryService employees;
    public EmployeeController(EmployeeQueryService employees) { this.employees=employees; }
    @GetMapping("/employees") @PreAuthorize("hasAnyRole('TRANSPORT_ADMIN','OPERATOR')")
    public PageResponse<EmployeeView> list(@RequestParam(required=false) EmploymentStatus status,
            @RequestParam(required=false) @Pattern(regexp="^[A-Z0-9-]{1,32}$") String officeCode,
            @RequestParam(defaultValue="0") @Min(0) int page, @RequestParam(defaultValue="20") @Min(1) @Max(200) int size) {
        return employees.list(TenantContext.currentUser().tenantId(),status,officeCode,page,size);
    }
    @GetMapping("/employees/{employeeId}") @PreAuthorize("hasAnyRole('TRANSPORT_ADMIN','OPERATOR','EMPLOYEE')")
    public EmployeeView get(@PathVariable @Pattern(regexp="[A-Za-z0-9_-]{1,64}") String employeeId) {
        var user=TenantContext.currentUser(); return employees.get(user.tenantId(),employeeId,user.role(),user.employeeId());
    }
    @GetMapping("/me") @PreAuthorize("hasRole('EMPLOYEE')")
    public EmployeeView me() {
        var user=TenantContext.currentUser(); return employees.get(user.tenantId(),user.employeeId(),user.role(),user.employeeId());
    }
    @GetMapping("/employees/{employeeId}/events") @PreAuthorize("hasAnyRole('TRANSPORT_ADMIN','OPERATOR')")
    public PageResponse<EmployeeQueryService.ReceiptView> events(@PathVariable @Pattern(regexp="[A-Za-z0-9_-]{1,64}") String employeeId,
            @RequestParam(defaultValue="0") @Min(0) int page, @RequestParam(defaultValue="20") @Min(1) @Max(200) int size) {
        return employees.receipts(TenantContext.currentUser().tenantId(),employeeId,page,size);
    }
}
