package com.moveinsync.rostersync.domain.recon;

import java.math.BigDecimal;
import java.util.List;

public record BrakeDecision(boolean tripped, String reason, List<String> reasons, BigDecimal ratio, String message) {
    public BrakeDecision { reasons = List.copyOf(reasons); }
}
