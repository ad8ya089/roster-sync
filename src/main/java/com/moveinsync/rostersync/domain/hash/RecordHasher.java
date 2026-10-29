package com.moveinsync.rostersync.domain.hash;

import com.moveinsync.rostersync.domain.model.EmployeeField;
import com.moveinsync.rostersync.domain.model.HrSnapshot;
import com.moveinsync.rostersync.domain.ownership.FieldOwnershipPolicy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.StringJoiner;

public final class RecordHasher {
    private final FieldOwnershipPolicy ownership;
    public RecordHasher(FieldOwnershipPolicy ownership) { this.ownership = ownership; }

    public String hash(HrSnapshot snapshot) {
        var canonical = new StringJoiner("\u001f");
        for (EmployeeField field : ownership.hrFields()) {
            String value = field.canonicalValue(snapshot.get(field));
            canonical.add(value == null ? "\u0000" : value);
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK must support SHA-256");
        }
    }
}
