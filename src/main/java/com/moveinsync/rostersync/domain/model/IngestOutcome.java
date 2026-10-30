package com.moveinsync.rostersync.domain.model;

import java.util.Locale;

public enum IngestOutcome {
    APPLIED, NO_CHANGE, DUPLICATE, STALE, CONFLICT, REJECTED, RETRYABLE_ERROR;

    public String metricTag() { return name().toLowerCase(Locale.ROOT); }
}
