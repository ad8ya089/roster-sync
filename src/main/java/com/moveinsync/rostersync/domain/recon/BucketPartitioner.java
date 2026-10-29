package com.moveinsync.rostersync.domain.recon;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.zip.CRC32;

public final class BucketPartitioner {
    private BucketPartitioner() { }

    public static int bucketOf(String employeeExternalId, int buckets) {
        Objects.requireNonNull(employeeExternalId, "employeeExternalId");
        if (buckets < 1) {
            throw new IllegalArgumentException("Bucket count must be positive");
        }
        CRC32 crc = new CRC32();
        crc.update(employeeExternalId.getBytes(StandardCharsets.UTF_8));
        return (int) (crc.getValue() % buckets);
    }
}
