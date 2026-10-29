package com.moveinsync.rostersync.domain.decision;

import com.moveinsync.rostersync.domain.command.UpdateEmployee;
import com.moveinsync.rostersync.domain.model.CurrentEmployeeState;
import com.moveinsync.rostersync.domain.model.HrSnapshot;
import com.moveinsync.rostersync.domain.ownership.FieldOwnershipPolicy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import net.jqwik.api.*;

import static com.moveinsync.rostersync.domain.DomainFixtures.*;
import static org.assertj.core.api.Assertions.*;

class ChangeDeciderPropertyTest {
    @Provide
    Arbitrary<List<Integer>> versions() {
        return Arbitraries.integers().between(1, 1_000_000).list().uniqueElements().ofMinSize(1).ofMaxSize(30);
    }

    @Property(tries = 1000, seed = "2026103001")
    void anyPermutationEndsAtTheHighestVersionAndReplayAppliesNothing(
            @ForAll("versions") List<Integer> versions, @ForAll long shuffleSeed) {
        var decider = new ChangeDecider(FieldOwnershipPolicy.defaults());
        var commands = new ArrayList<>(versions.stream().map(version -> new UpdateEmployee("E1", version, null, null, payload(version))).toList());
        Collections.shuffle(commands, new Random(shuffleSeed));
        var appliedVersions = new HashSet<Long>();
        CurrentEmployeeState current = CurrentEmployeeState.missing();
        for (var command : commands) {
            current = switch (decider.decide(current, command, DecisionMode.EVENT)) {
                case ApplyDecision.Apply apply -> {
                    assertThat(appliedVersions.add(command.version())).isTrue();
                    yield state(command.version(), apply.newSnapshot());
                }
                case ApplyDecision.Stale ignored -> current;
                case ApplyDecision.Duplicate ignored -> current;
                case ApplyDecision.Conflict ignored -> throw new AssertionError("Distinct versions cannot conflict");
            };
        }
        int highest = Collections.max(versions);
        assertThat(current.hrVersion()).isEqualTo(highest);
        assertThat(current.snapshot()).isEqualTo(payload(highest));
        assertThat(appliedVersions).hasSizeLessThanOrEqualTo(versions.size());
        for (var command : commands) assertThat(decider.decide(current, command, DecisionMode.EVENT)).isNotInstanceOf(ApplyDecision.Apply.class);
    }

    private HrSnapshot payload(int version) {
        return new HrSnapshot("Version " + version, "v" + version + "@example.test", "+919800000001", "M1", "BLR-1",
                "S" + (version % 20), version % 3 == 0 ? "INACTIVE" : "ACTIVE");
    }
}
