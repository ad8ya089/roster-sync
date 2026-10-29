package com.moveinsync.rostersync.domain.recon;

import java.math.BigDecimal;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.*;

class SafetyBrakeTest {
    private final SafetyBrake brake = new SafetyBrake();
    private final BrakeSettings defaults = new BrakeSettings(new BigDecimal("0.30"), null);

    @ParameterizedTest
    @CsvSource({"10000,2999,false", "10000,3000,true", "10000,3001,true", "10,3,true", "0,0,false"})
    void comparesInclusiveBoundariesExactly(long active, long missing, boolean expected) {
        assertThat(brake.evaluate(new BrakeInput(active, missing, 0, null, null), defaults).tripped()).isEqualTo(expected);
    }

    @Test
    void countComparisonsCannotOverflowAndDoNotRoundBeforeComparing() {
        long missing = (Long.MAX_VALUE / 10) * 3;
        assertThat(brake.evaluate(new BrakeInput(Long.MAX_VALUE, missing, 0, null, null), defaults).tripped()).isFalse();
        assertThat(brake.evaluate(new BrakeInput(Long.MAX_VALUE, missing + 100, 0, null, null), defaults).tripped()).isTrue();
    }

    @Test
    void formatsTheExactApiMessageWithEnglishLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            var result = brake.evaluate(new BrakeInput(200_000, 80_010, 120_000, null, null), defaults);
            assertThat(result.message()).isEqualTo("File appears to be missing 80,010 of 200,000 active employees (40.0%). Threshold is 30.0%. No one has been deactivated. Approve or reject this run.");
            assertThat(result.ratio()).isEqualByComparingTo("0.40005");
        } finally { Locale.setDefault(previous); }
    }

    @Test
    void prioritizesCountMismatchThenRatioThenCountAndExposesEveryReason() {
        var result = brake.evaluate(new BrakeInput(100, 40, 60, 100L, 100L), new BrakeSettings(new BigDecimal("0.30"), 10L));
        assertThat(result.reason()).isEqualTo("ROW_COUNT_MISMATCH");
        assertThat(result.reasons()).containsExactly("ROW_COUNT_MISMATCH", "MISSING_RATIO", "MISSING_COUNT");
        assertThat(result.message()).contains("File row count mismatch: read 60 rows.");
        assertThatThrownBy(() -> result.reasons().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(brake.evaluate(new BrakeInput(100, 40, 60, null, null), new BrakeSettings(new BigDecimal("0.30"), 10L)).reason()).isEqualTo("MISSING_RATIO");
        var countOnly = brake.evaluate(new BrakeInput(100, 10, 90, null, null), new BrakeSettings(new BigDecimal("0.50"), 10L));
        assertThat(countOnly.reason()).isEqualTo("MISSING_COUNT");
        assertThat(countOnly.message()).contains("Missing count threshold is 10.");
    }

    @Test
    void zeroActiveStillTripsOnTrailerAndExpectedCountMismatchesInEitherDirection() {
        assertThat(brake.evaluate(new BrakeInput(0, 0, 0, 1L, null), defaults).reason()).isEqualTo("ROW_COUNT_MISMATCH");
        assertThat(brake.evaluate(new BrakeInput(0, 0, 0, null, 1L), defaults).reason()).isEqualTo("ROW_COUNT_MISMATCH");
        assertThat(brake.evaluate(new BrakeInput(0, 0, 2, null, 1L), defaults).reason()).isEqualTo("ROW_COUNT_MISMATCH");
        var pass = brake.evaluate(new BrakeInput(0, 0, 1, 1L, 1L), new BrakeSettings(new BigDecimal("0.30"), 1L));
        assertThat(pass.tripped()).isFalse(); assertThat(pass.reasons()).isEmpty(); assertThat(pass.reason()).isNull();
        assertThat(pass.ratio()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void thresholdChangesTakeEffectForTheVeryNextEvaluation() {
        var input = new BrakeInput(100, 40, 60, null, null);
        assertThat(brake.evaluate(input, defaults).tripped()).isTrue();
        assertThat(brake.evaluate(input, new BrakeSettings(new BigDecimal("0.50"), null)).tripped()).isFalse();
    }

    @Test
    void rejectsInvalidThresholdsAndCounts() {
        for (String ratio : new String[]{"0", "-0.1", "1.01"}) assertThatIllegalArgumentException().isThrownBy(() -> new BrakeSettings(new BigDecimal(ratio), null));
        assertThatIllegalArgumentException().isThrownBy(() -> new BrakeSettings(BigDecimal.ONE, 0L));
        assertThatNullPointerException().isThrownBy(() -> new BrakeSettings(null, null));
        assertThatIllegalArgumentException().isThrownBy(() -> new BrakeInput(-1, 0, 0, null, null));
        assertThatIllegalArgumentException().isThrownBy(() -> new BrakeInput(1, -1, 0, null, null));
        assertThatIllegalArgumentException().isThrownBy(() -> new BrakeInput(1, 2, 0, null, null));
        assertThatIllegalArgumentException().isThrownBy(() -> new BrakeInput(1, 0, -1, null, null));
        assertThatIllegalArgumentException().isThrownBy(() -> new BrakeInput(1, 0, 0, -1L, null));
        assertThatIllegalArgumentException().isThrownBy(() -> new BrakeInput(1, 0, 0, null, -1L));
    }
}
