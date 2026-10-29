package com.moveinsync.rostersync.domain.recon;

import java.util.List;

public record BucketDiff(List<PlannedAction> actions, DiffCounters counters) {
    public BucketDiff { actions = List.copyOf(actions); }
}
