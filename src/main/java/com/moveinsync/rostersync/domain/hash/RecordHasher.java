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
        return digest(canonical(snapshot));
    }

    public String hashEvent(com.moveinsync.rostersync.domain.command.HrCommand command) {
        // Resolve against an empty store: DEACTIVATE's receipt hash must not depend on mutable DB state.
        return digest(command.type().name() + "\u001f" + canonical(command.resolveSnapshot(java.util.Optional.empty())));
    }

    private String canonical(HrSnapshot snapshot) {
        var canonical = new StringJoiner("\u001f");
        for (EmployeeField field : ownership.hrFields()) {
            String value = field.canonicalValue(snapshot.get(field));
            canonical.add(value == null ? "\u0000" : value);
        }
        return canonical.toString();
    }

    private String digest(String canonical) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK must support SHA-256");
        }
    }
}
