package com.moveinsync.rostersync.domain.recon;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class BucketPartitionerTest {
    @ParameterizedTest
    @CsvSource({"E1001,185", "E1002,3", "E1003,149", "E1004,54", "E1005,160",
            "E000123,234", "S000001,135", "employee_42,132"})
    void matchesIndependentlyComputedCrc32GoldenValues(String employeeId, int expectedBucket) {
        assertThat(BucketPartitioner.bucketOf(employeeId, 256)).isEqualTo(expectedBucket);
    }

    @Test
    void distributesTwoHundredThousandIdsAcrossAllBuckets() {
        int[] counts = new int[256];
        for (int index = 1; index <= 200_000; index++) {
            counts[BucketPartitioner.bucketOf("S" + index, counts.length)]++;
        }
        for (int count : counts) {
            assertThat(count).isBetween(600, 950);
        }
    }

    @Test
    void supportsConfiguredBucketCountsAndRejectsInvalidInput() {
        assertThat(BucketPartitioner.bucketOf("E1001", 512)).isEqualTo(185);
        assertThat(BucketPartitioner.bucketOf("E1001", 1)).isZero();
        assertThatIllegalArgumentException().isThrownBy(() -> BucketPartitioner.bucketOf("E1001", 0));
        assertThatIllegalArgumentException().isThrownBy(() -> BucketPartitioner.bucketOf("E1001", -1));
        assertThatNullPointerException().isThrownBy(() -> BucketPartitioner.bucketOf(null, 256));
    }
}
