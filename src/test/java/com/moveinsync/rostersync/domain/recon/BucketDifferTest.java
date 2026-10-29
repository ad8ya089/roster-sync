package com.moveinsync.rostersync.domain.recon;

import com.moveinsync.rostersync.domain.hash.RecordHasher;
import com.moveinsync.rostersync.domain.model.*;
import com.moveinsync.rostersync.domain.ownership.FieldOwnershipPolicy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static com.moveinsync.rostersync.domain.DomainFixtures.*;
import static org.assertj.core.api.Assertions.*;

class BucketDifferTest {
    private final RecordHasher hasher = new RecordHasher(FieldOwnershipPolicy.defaults());
    private final BucketDiffer differ = new BucketDiffer(hasher);

    @Test
    void implementsTheEntireDecisionTableWithoutMutatingInputs() {
        var rows = List.of(db("update", 1, snapshot("S1")), db("heal", 2, snapshot("S1")), db("stale", 3, snapshot("S1")),
                db("same", 4, snapshot("S1")), db("missing", 1, snapshot("S1")),
                new DbRow("fresh", 1, EmploymentStatus.ACTIVE, NOW.plusSeconds(1), snapshot("S1")),
                db("inactive", 1, snapshot("S1").withStatus("INACTIVE")), db("invalid", 1, snapshot("S1")),
                db("equal-time", 1, snapshot("S1")));
        Map<String, FileRow> files = new LinkedHashMap<>();
        for (FileRow row : List.of(file("update", 2, "S2"), file("heal", 2, "S2"), file("stale", 2, "S2"),
                file("same", 9, "S1"), file("invalid", 2, "S2"), file("create", 1, "S1"), file("invalid-new", 1, "S1"))) files.put(row.employeeId(), row);
        var original = Map.copyOf(files);
        BucketDiff result = differ.diff(files, rows, Set.of("invalid", "invalid-new"), NOW);
        assertThat(files).isEqualTo(original);
        assertThat(result.counters()).isEqualTo(new DiffCounters(8, 2, 1, 1, 1, 1, 1));
        assertThat(result.actions()).extracting(PlannedAction::action)
                .containsExactly(ActionType.UPDATE, ActionType.HEAL, ActionType.DEACTIVATE, ActionType.DEACTIVATE, ActionType.CREATE);
        assertThat(result.actions()).extracting(PlannedAction::employeeId).doesNotContain("fresh", "inactive", "invalid", "invalid-new", "stale", "same");
        var update = result.actions().getFirst();
        assertThat(update.fileVersion()).isEqualTo(2);
        assertThat(update.expectedDbVersion()).isEqualTo(1);
        assertThat(update.payload()).isEqualTo(snapshot("S2"));
        var deactivate = result.actions().get(2);
        assertThat(deactivate.payload()).isNull();
        assertThat(deactivate.fileVersion()).isNull();
        assertThat(deactivate.expectedDbVersion()).isEqualTo(1);
        assertThat(result.actions().getLast().expectedDbVersion()).isNull();
        assertThatThrownBy(() -> result.actions().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(files.get("create").toString()).doesNotContain("Test Person", "test@example.test");
        assertThat(rows.getFirst().toString()).doesNotContain("Test Person", "test@example.test");
        assertThat(update.toString()).doesNotContain("Test Person", "test@example.test");
    }

    @Test
    void handlesEmptyInputsAndInvalidIdsThatAreOnlyPresenceMarkers() {
        assertThat(differ.diff(Map.of(), List.of(), Set.of(), NOW).counters()).isEqualTo(new DiffCounters(0, 0, 0, 0, 0, 0, 0));
        assertThat(differ.diff(Map.of(), List.of(db("invalid", 1, snapshot("S1"))), Set.of("invalid"), NOW).actions()).isEmpty();
    }

    private FileRow file(String id, long version, String shift) { return new FileRow(id, version, hasher.hash(snapshot(shift)), snapshot(shift)); }
    private DbRow db(String id, long version, HrSnapshot value) { return new DbRow(id, version, EmploymentStatus.valueOf(value.status()), NOW, value); }
}
