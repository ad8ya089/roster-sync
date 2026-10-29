package com.moveinsync.rostersync.domain.recon;

public record BrakeInput(long activeBefore, long missingActive, long dataRowsRead, Long trailerRowCount, Long expectedRowCount) {
    public BrakeInput {
        if (activeBefore < 0 || missingActive < 0 || missingActive > activeBefore || dataRowsRead < 0
                || (trailerRowCount != null && trailerRowCount < 0) || (expectedRowCount != null && expectedRowCount < 0)) {
            throw new IllegalArgumentException("Invalid brake counts");
        }
    }
}
