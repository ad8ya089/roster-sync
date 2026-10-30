package com.moveinsync.rostersync.domain.hash;

import com.moveinsync.rostersync.domain.model.*;
import com.moveinsync.rostersync.domain.ownership.FieldOwnershipPolicy;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static com.moveinsync.rostersync.domain.DomainFixtures.*;
import static org.assertj.core.api.Assertions.*;

class RecordHasherTest {
    @org.junit.jupiter.api.Test
    void receiptHashesIncludeTypeButExcludeInformationalMetadata() {
        var hasher = new RecordHasher(com.moveinsync.rostersync.domain.ownership.FieldOwnershipPolicy.defaults());
        var snapshot = new com.moveinsync.rostersync.domain.model.HrSnapshot("Name", "person@example.test", null, null, "BLR-1", "S1", "ACTIVE");
        var first = new com.moveinsync.rostersync.domain.command.UpdateEmployee("E1", 7, "first", java.time.Instant.EPOCH, snapshot);
        var replay = new com.moveinsync.rostersync.domain.command.UpdateEmployee("E1", 7, "new-id", java.time.Instant.EPOCH.plusSeconds(1), snapshot);
        var create = new com.moveinsync.rostersync.domain.command.CreateEmployee("E1", 7, null, null, snapshot);
        assertThat(hasher.hashEvent(first)).isEqualTo(hasher.hashEvent(replay)).isNotEqualTo(hasher.hashEvent(create));
    }
    private final RecordHasher hasher = new RecordHasher(FieldOwnershipPolicy.defaults());

    @Test
    void canonicalHashMatchesIndependentGoldenValue() {
        assertThat(hasher.hash(snapshot("S1"))).isEqualTo("eb24db2e9315b212670e629186b494fd8c4f03794a9c52a2be804952a18edce2");
        assertThat(hasher.hash(snapshot("S1"))).isEqualTo(hasher.hash(snapshot("S1")));
    }

    @Test
    void trimsStringsNormalizesEmailAndKeepsDeclarationOrder() {
        var padded = new HrSnapshot("  Test Person ", " TEST@EXAMPLE.TEST ", "+919800000001", "M1", "BLR-1", "S1", "ACTIVE");
        assertThat(hasher.hash(padded)).isEqualTo(hasher.hash(snapshot("S1")));
        assertThat(hasher.hash(snapshot("S2"))).isNotEqualTo(hasher.hash(snapshot("S1")));
    }

    @Test
    void excludesTransportFieldsAndOwnershipMovesChangeTheHashInput() {
        var transport = new HrSnapshot("Test Person", "test@example.test", "+919800000001", "M1", "BLR-1", "S1", "ACTIVE", "Pickup", "Assistance");
        assertThat(hasher.hash(transport)).isEqualTo(hasher.hash(snapshot("S1")));
        var moved = new RecordHasher(new FieldOwnershipPolicy(Set.of(EmployeeField.PHONE, EmployeeField.PICKUP_POINT, EmployeeField.SPECIAL_ASSISTANCE)));
        var phoneChanged = new HrSnapshot("Test Person", "test@example.test", "+919800000002", "M1", "BLR-1", "S1", "ACTIVE");
        assertThat(moved.hash(phoneChanged)).isEqualTo(moved.hash(snapshot("S1")));
        assertThat(new RecordHasher(new FieldOwnershipPolicy(Set.of())).hash(transport)).isNotEqualTo(hasher.hash(transport));
    }

    @Test
    void nullAndEmptyAreDifferentAndDigestInstancesAreThreadSafe() throws Exception {
        var emptyName = new HrSnapshot("", null, null, null, null, null, "INACTIVE");
        assertThat(hasher.hash(emptyName)).isNotEqualTo(hasher.hash(HrSnapshot.tombstone()));
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(4)) {
            var tasks = java.util.stream.IntStream.range(0, 50).<java.util.concurrent.Callable<String>>mapToObj(index -> () -> hasher.hash(snapshot("S1"))).toList();
            for (var result : executor.invokeAll(tasks)) assertThat(result.get()).isEqualTo(hasher.hash(snapshot("S1")));
        }
    }
}
