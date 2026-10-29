package com.moveinsync.rostersync.domain.recon;

import java.math.BigDecimal;
import java.util.Objects;

public record BrakeSettings(BigDecimal maxMissingRatio, Long maxMissingCount) {
    public BrakeSettings {
        Objects.requireNonNull(maxMissingRatio, "maxMissingRatio");
        if (maxMissingRatio.signum() <= 0 || maxMissingRatio.compareTo(BigDecimal.ONE) > 0
                || (maxMissingCount != null && maxMissingCount < 1)) throw new IllegalArgumentException("Invalid brake thresholds");
    }
}
