package com.moveinsync.rostersync.api.ops;
import com.moveinsync.rostersync.application.ops.OpsSummaryService;
import com.moveinsync.rostersync.infrastructure.security.TenantContext;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/ops/summary")
public class OpsSummaryController {
    private final OpsSummaryService summary;
    public OpsSummaryController(OpsSummaryService summary){this.summary=summary;}
    @GetMapping @PreAuthorize("hasRole('OPERATOR')")
    public OpsSummaryService.Summary get(){return summary.get(TenantContext.currentUser().tenantId());}
}
