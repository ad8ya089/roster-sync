package com.moveinsync.rostersync.infrastructure.metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class RosterMetricsTest {
    @Test void runCountersExcludeIntermediateWorkerStates(){
        var registry=new SimpleMeterRegistry();var metrics=new RosterMetrics(registry,Clock.systemUTC());
        for(String status:java.util.List.of("UPLOADED","DIFFING","APPLYING","PLANNED","AWAITING_APPROVAL","COMPLETED","REJECTED","FAILED"))metrics.run(status);
        assertThat(registry.find("recon.runs").counters()).hasSize(5).allSatisfy(counter->assertThat(counter.count()).isEqualTo(1));
        assertThat(registry.find("recon.runs").tag("status","UPLOADED").counter()).isNull();metrics.close();registry.close();
    }
    @Test void negativeLagIsIgnoredAndPositiveLagHasPercentiles(){
        var registry=new SimpleMeterRegistry();var now=Instant.parse("2026-10-30T10:00:00Z");var metrics=new RosterMetrics(registry,Clock.fixed(now,ZoneOffset.UTC));
        metrics.event("applied","CREATE",1000000,now.plusSeconds(1));assertThat(registry.find("roster.ingest.lag").timer()).isNull();
        metrics.event("applied","CREATE",2000000,now.minusSeconds(2));assertThat(registry.get("roster.ingest.lag").timer().count()).isEqualTo(1);
        assertThat(metrics.p99ApplyMs()).isPositive();assertThat(metrics.p99LagSeconds()).isPositive();metrics.close();
    }
    @Test void untrustedReasonsCannotCreateUnboundedTags(){
        var registry=new SimpleMeterRegistry();var metrics=new RosterMetrics(registry,Clock.systemUTC());metrics.rejection("private@example.test","api");
        assertThat(registry.get("roster.rejections").tag("reason","INVALID_FORMAT").counter().count()).isEqualTo(1);
        assertThat(registry.getMeters()).allSatisfy(meter->assertThat(meter.getId().getTags()).allSatisfy(tag->assertThat(tag.getValue()).doesNotContain("private@example.test")));metrics.close();
    }
}
