package com.moveinsync.rostersync.domain.recon;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Locale;

public final class SafetyBrake {
    public BrakeDecision evaluate(BrakeInput input, BrakeSettings settings) {
        var reasons = new ArrayList<String>();
        if ((input.trailerRowCount() != null && input.trailerRowCount() != input.dataRowsRead())
                || (input.expectedRowCount() != null && input.expectedRowCount() != input.dataRowsRead())) reasons.add("ROW_COUNT_MISMATCH");
        BigDecimal missing = BigDecimal.valueOf(input.missingActive());
        BigDecimal active = BigDecimal.valueOf(input.activeBefore());
        BigDecimal ratio = input.activeBefore() == 0 ? BigDecimal.ZERO : missing.divide(active, 16, RoundingMode.HALF_UP);
        if (input.activeBefore() > 0) {
            // Compare unrounded counts against the exact decimal threshold, never a rounded ratio.
            if (missing.compareTo(settings.maxMissingRatio().multiply(active)) >= 0) reasons.add("MISSING_RATIO");
            if (settings.maxMissingCount() != null && input.missingActive() >= settings.maxMissingCount()) reasons.add("MISSING_COUNT");
        }
        if (reasons.isEmpty()) return new BrakeDecision(false, null, reasons, ratio, "Reconciliation safety checks passed.");
        String reason = reasons.getFirst();
        String prefix = String.format(Locale.ENGLISH, "File appears to be missing %,d of %,d active employees (%.1f%%). ",
                input.missingActive(), input.activeBefore(), ratio.multiply(BigDecimal.valueOf(100)));
        String threshold = switch (reason) {
            case "ROW_COUNT_MISMATCH" -> String.format(Locale.ENGLISH, "File row count mismatch: read %,d rows. ", input.dataRowsRead());
            case "MISSING_COUNT" -> String.format(Locale.ENGLISH, "Missing count threshold is %,d. ", settings.maxMissingCount());
            default -> String.format(Locale.ENGLISH, "Threshold is %.1f%%. ", settings.maxMissingRatio().multiply(BigDecimal.valueOf(100)));
        };
        return new BrakeDecision(true, reason, reasons, ratio, prefix + threshold
                + "No one has been deactivated. Approve or reject this run.");
    }
}
