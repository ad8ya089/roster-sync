package com.moveinsync.rostersync.api.recon;
import com.moveinsync.rostersync.application.recon.*;
import com.moveinsync.rostersync.application.employee.PageResponse;
import com.moveinsync.rostersync.infrastructure.security.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController @RequestMapping("/api/v1/reconciliation/runs")
public class ReconciliationController {
    public enum Mode {ALL,SKIP_DEACTIVATIONS}
    public enum Status {UPLOADED,DIFFING,PLANNED,AWAITING_APPROVAL,APPLYING,COMPLETED,REJECTED,FAILED}
    private final ReconciliationUploadService uploads;private final ReconciliationQueryService queries;private final ReconciliationDecisionService decisions;
    public ReconciliationController(ReconciliationUploadService uploads,ReconciliationQueryService queries,ReconciliationDecisionService decisions){this.uploads=uploads;this.queries=queries;this.decisions=decisions;}
    @PostMapping(consumes=MediaType.MULTIPART_FORM_DATA_VALUE) @ResponseStatus(HttpStatus.ACCEPTED) @PreAuthorize("hasAnyRole('HR_SYSTEM','OPERATOR')")
    public ReconciliationUploadService.Accepted upload(@RequestParam(required=false) MultipartFile file,@RequestParam Instant asOf,@RequestParam(required=false) @Min(0) Integer expectedRowCount,@RequestParam(defaultValue="false") boolean dryRun) throws IOException {
        var user=TenantContext.currentUser();return uploads.upload(user.tenantId(),user.username(),file,asOf,expectedRowCount,dryRun);
    }
    @GetMapping("/{runId}") @PreAuthorize("hasAnyRole('HR_SYSTEM','OPERATOR','TRANSPORT_ADMIN')")
    public ReconciliationQueryService.RunView get(@PathVariable UUID runId){var user=TenantContext.currentUser();return queries.get(user.tenantId(),runId,user.role(),user.username());}
    @GetMapping @PreAuthorize("hasAnyRole('HR_SYSTEM','OPERATOR','TRANSPORT_ADMIN')")
    public PageResponse<ReconciliationQueryService.RunView> list(@RequestParam(required=false) Status status,@RequestParam(defaultValue="0") @Min(0) int page,@RequestParam(defaultValue="20") @Min(1) @Max(200) int size) {
        var user=TenantContext.currentUser();return queries.list(user.tenantId(),status==null?null:status.name(),page,size,user.role(),user.username());
    }
    @GetMapping("/{runId}/rejections") @PreAuthorize("hasAnyRole('HR_SYSTEM','OPERATOR','TRANSPORT_ADMIN')")
    public PageResponse<ReconciliationQueryService.Rejection> rejections(@PathVariable UUID runId,@RequestParam(defaultValue="0") @Min(0) int page,@RequestParam(defaultValue="20") @Min(1) @Max(200) int size) {
        var user=TenantContext.currentUser();return queries.rejections(user.tenantId(),runId,page,size,user.role(),user.username());
    }
    @PostMapping("/{runId}/approve") @ResponseStatus(HttpStatus.ACCEPTED) @PreAuthorize("hasRole('OPERATOR')")
    public Map<String,Object> approve(@PathVariable UUID runId,@Valid @RequestBody Approval body) {
        var user=TenantContext.currentUser();decisions.decide(user.tenantId(),runId,true,body.mode().name(),user.username(),body.note());return Map.of("runId",runId,"status","APPLYING");
    }
    @PostMapping("/{runId}/reject") @PreAuthorize("hasRole('OPERATOR')")
    public Map<String,Object> reject(@PathVariable UUID runId,@Valid @RequestBody Rejection body) {
        var user=TenantContext.currentUser();decisions.decide(user.tenantId(),runId,false,null,user.username(),body.note());return Map.of("runId",runId,"status","REJECTED");
    }
    public record Approval(@NotNull Mode mode,@Size(max=500) String note) { @Override public String toString(){return "Approval[mode="+mode+"]";} }
    public record Rejection(@Size(max=500) String note) { @Override public String toString(){return "Rejection[]";} }
}
