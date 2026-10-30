package com.moveinsync.rostersync.domain.model;

import com.moveinsync.rostersync.domain.decision.ApplyDecision;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class IngestOutcomeTest {
    @Test void noChangeHasItsOwnWireAndMetricOutcome() {
        assertThat(IngestOutcome.NO_CHANGE.name()).isEqualTo("NO_CHANGE");
        assertThat(IngestOutcome.NO_CHANGE.metricTag()).isEqualTo("no_change");
        assertThat(IngestOutcome.values()).extracting(IngestOutcome::metricTag)
                .containsExactly("applied", "no_change", "duplicate", "stale", "conflict", "rejected", "retryable_error");
        assertThat(new ApplyDecision.Duplicate().outcome()).isEqualTo(IngestOutcome.DUPLICATE);
        assertThat(new ApplyDecision.Stale(7).outcome()).isEqualTo(IngestOutcome.STALE);
        assertThat(new ApplyDecision.Conflict(7).outcome()).isEqualTo(IngestOutcome.CONFLICT);
    }
}
