package com.moveinsync.rostersync.api.hr;
import com.moveinsync.rostersync.application.ingest.*;
import com.moveinsync.rostersync.infrastructure.security.TenantContext;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/hr/events")
public class HrEventController {
    private final EventIngestionService ingestion;
    public HrEventController(EventIngestionService ingestion) { this.ingestion=ingestion; }
    @PostMapping @PreAuthorize("hasRole('HR_SYSTEM')")
    public IngestResult ingest(@RequestBody EventBatch batch) { return ingestion.ingest(TenantContext.currentUser().tenantId(), batch.events()); }
    public record EventBatch(List<Object> events) {
        @Override public String toString() { return "EventBatch[count=" + (events == null ? 0 : events.size()) + "]"; }
    }
}
