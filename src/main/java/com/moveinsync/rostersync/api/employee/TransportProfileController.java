package com.moveinsync.rostersync.api.employee;
import com.moveinsync.rostersync.application.employee.*;
import com.moveinsync.rostersync.infrastructure.security.TenantContext;
import jakarta.validation.constraints.Pattern;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/employees")
public class TransportProfileController {
    private final TransportProfileService profiles;
    public TransportProfileController(TransportProfileService profiles) { this.profiles=profiles; }
    @PatchMapping("/{employeeId}/transport-fields") @PreAuthorize("hasRole('TRANSPORT_ADMIN')")
    public EmployeeView patch(@PathVariable @Pattern(regexp="[A-Za-z0-9_-]{1,64}") String employeeId, @RequestBody Map<String,Object> body) {
        return profiles.patch(TenantContext.currentUser().tenantId(),employeeId,body);
    }
}
