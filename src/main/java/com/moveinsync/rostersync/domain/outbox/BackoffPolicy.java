package com.moveinsync.rostersync.domain.outbox;

import java.time.Duration;
import java.util.Objects;

public final class BackoffPolicy {
    private final Duration initialDelay;
    private final Duration maximumDelay;
    private final int maxAttempts;

    public BackoffPolicy(int maxAttempts) { this(Duration.ofSeconds(1), Duration.ofSeconds(300), maxAttempts); }

    public BackoffPolicy(Duration initialDelay, Duration maximumDelay, int maxAttempts) {
        Objects.requireNonNull(initialDelay, "initialDelay");
        Objects.requireNonNull(maximumDelay, "maximumDelay");
        if (initialDelay.isNegative() || initialDelay.isZero() || maximumDelay.compareTo(initialDelay) < 0 || maxAttempts < 1) {
            throw new IllegalArgumentException("Invalid retry settings");
        }
        this.initialDelay = initialDelay;
        this.maximumDelay = maximumDelay;
        this.maxAttempts = maxAttempts;
    }

    public Duration delay(int failedAttempts) {
        if (failedAttempts < 1) throw new IllegalArgumentException("Failure count must be positive");
        Duration delay = initialDelay;
        for (int attempt = 1; attempt < failedAttempts && delay.compareTo(maximumDelay) < 0; attempt++) {
            if (delay.compareTo(maximumDelay.dividedBy(2)) > 0) return maximumDelay;
            delay = delay.multipliedBy(2);
        }
        return delay;
    }

    public boolean dead(int failedAttempts) { return failedAttempts >= maxAttempts; }
}
