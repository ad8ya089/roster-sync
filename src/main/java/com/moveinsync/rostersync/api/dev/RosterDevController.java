package com.moveinsync.rostersync.api.dev;
import com.moveinsync.rostersync.application.recon.*;
import com.moveinsync.rostersync.infrastructure.security.TenantContext;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController @Profile("demo") @RequestMapping("/api/v1/dev")
public class RosterDevController {
    private final DemoRosterSeeder seeder;private final DemoRosterExportService exporter;
    public RosterDevController(DemoRosterSeeder seeder,DemoRosterExportService exporter){this.seeder=seeder;this.exporter=exporter;}
    @PostMapping("/seed-employees") @PreAuthorize("hasRole('OPERATOR')")
    public Map<String,Object> seed(@RequestParam @Min(1) @Max(200000) int count){return seeder.seed(TenantContext.currentUser().tenantId(),count);}
    @GetMapping(value="/export.csv",produces="text/csv") @PreAuthorize("hasRole('OPERATOR')")
    public ResponseEntity<StreamingResponseBody> export(@RequestParam(required=false) @Min(0) Long truncateAt,
            @RequestParam(defaultValue="0") @DecimalMin("0") @DecimalMax("100") BigDecimal mutatePercent,
            @RequestParam(defaultValue="0") @DecimalMin("0") @DecimalMax("100") BigDecimal dropPercent,
            @RequestParam(defaultValue="0") @DecimalMin("0") @DecimalMax("100") BigDecimal newPercent,@RequestParam(defaultValue="42") long seed) {
        var tenantId=TenantContext.currentUser().tenantId();var spec=new RosterFileGenerator.Spec(truncateAt,mutatePercent,dropPercent,newPercent,seed);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv")).body(output->exporter.write(tenantId,output,spec));
    }
}
