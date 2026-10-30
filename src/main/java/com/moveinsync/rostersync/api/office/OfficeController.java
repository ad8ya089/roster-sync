package com.moveinsync.rostersync.api.office;

import com.moveinsync.rostersync.application.employee.OfficeService;
import com.moveinsync.rostersync.application.employee.OfficeService.OfficeView;
import com.moveinsync.rostersync.infrastructure.security.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/offices")
public class OfficeController {
    private final OfficeService offices;
    public OfficeController(OfficeService offices) { this.offices = offices; }

    @GetMapping
    @PreAuthorize("hasAnyRole('TRANSPORT_ADMIN', 'OPERATOR')")
    public List<OfficeView> list(@RequestParam(defaultValue = "true") boolean includeInactive) {
        return offices.list(TenantContext.currentUser().tenantId(), includeInactive);
    }

    @GetMapping("/{code}")
    @PreAuthorize("hasAnyRole('TRANSPORT_ADMIN', 'OPERATOR')")
    public OfficeView get(@PathVariable @Pattern(regexp = "^[A-Z0-9-]{1,32}$") String code) {
        return offices.get(TenantContext.currentUser().tenantId(), code);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('TRANSPORT_ADMIN')")
    public OfficeView create(@Valid @RequestBody CreateOffice request) {
        return offices.create(TenantContext.currentUser().tenantId(), request.code(), request.name());
    }

    @PatchMapping("/{code}")
    @PreAuthorize("hasRole('TRANSPORT_ADMIN')")
    public OfficeView patch(@PathVariable @Pattern(regexp = "^[A-Z0-9-]{1,32}$") String code,
                            @Valid @RequestBody PatchOffice request) {
        return offices.patch(TenantContext.currentUser().tenantId(), code, request.name(), request.active());
    }

    public record CreateOffice(@NotBlank @Pattern(regexp = "^[A-Z0-9-]{1,32}$") String code,
                               @NotBlank @Size(min = 1, max = 200) String name) {
        @Override public String toString() { return "CreateOffice[code=" + code + "]"; }
    }
    public record PatchOffice(@Size(min = 1, max = 200) @Pattern(regexp = "(?s).*\\S.*") String name, Boolean active) {
        @Override public String toString() { return "PatchOffice[active=" + active + "]"; }
    }
}
