package com.moveinsync.rostersync.api.settings;
import com.moveinsync.rostersync.application.recon.SettingsService;
import com.moveinsync.rostersync.infrastructure.security.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/settings/reconciliation")
public class ReconciliationSettingsController {
    private final SettingsService settings;
    public ReconciliationSettingsController(SettingsService settings){this.settings=settings;}
    @GetMapping @PreAuthorize("hasRole('OPERATOR')")
    public SettingsService.View get(){return settings.get(TenantContext.currentUser().tenantId());}
    @PutMapping @PreAuthorize("hasRole('OPERATOR')")
    public SettingsService.View set(@Valid @RequestBody Update body){var user=TenantContext.currentUser();return settings.set(user.tenantId(),body.maxMissingRatio(),body.maxMissingCount(),user.username());}
    public record Update(@NotNull @DecimalMin(value="0",inclusive=false) @DecimalMax("1") @Digits(integer=1,fraction=3) BigDecimal maxMissingRatio,@Min(1) Integer maxMissingCount) { }
}
