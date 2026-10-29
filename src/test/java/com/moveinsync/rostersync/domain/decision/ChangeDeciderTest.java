package com.moveinsync.rostersync.domain.decision;

import com.moveinsync.rostersync.domain.command.*;
import com.moveinsync.rostersync.domain.model.*;
import com.moveinsync.rostersync.domain.outbox.OutboxEventType;
import com.moveinsync.rostersync.domain.ownership.FieldOwnershipPolicy;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static com.moveinsync.rostersync.domain.DomainFixtures.*;
import static org.assertj.core.api.Assertions.*;

class ChangeDeciderTest {
    private final ChangeDecider decider = new ChangeDecider(FieldOwnershipPolicy.defaults());

    @Test
    void versionOneAndUnknownUpdateCreateRows() {
        for (HrCommand command : new HrCommand[]{new CreateEmployee("E1", 1, null, null, snapshot("S1")), update(1, snapshot("S1"))}) {
            var result = applied(decider.decide(CurrentEmployeeState.missing(), command, DecisionMode.EVENT));
            assertThat(result.eventType()).isEqualTo(OutboxEventType.CREATED);
            assertThat(result.heal()).isFalse();
            assertThat(result.changedFields()).isNotEmpty();
        }
    }

    @Test
    void newerAppliesAndChangedFieldsFollowDeclarationOrder() {
        var newer = new HrSnapshot("Changed", "NEW@EXAMPLE.TEST", "+919800000001", "M1", "BLR-2", "S2", "ACTIVE");
        var result = applied(decider.decide(state(5, snapshot("S1")), update(7, newer), DecisionMode.EVENT));
        assertThat(result.changedFields()).containsExactly(EmployeeField.NAME, EmployeeField.EMAIL, EmployeeField.OFFICE_CODE, EmployeeField.SHIFT_CODE);
        assertThat(result.eventType()).isEqualTo(OutboxEventType.UPDATED);
        assertThatThrownBy(() -> result.changedFields().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void lowerVersionsAreStaleIncludingLongMaxValueBoundary() {
        assertThat(decider.decide(state(7, snapshot("S1")), update(6, snapshot("S2")), DecisionMode.EVENT))
                .isEqualTo(new ApplyDecision.Stale(7));
        assertThat(decider.decide(state(Long.MAX_VALUE, snapshot("S1")), update(1, snapshot("S2")), DecisionMode.EVENT))
                .isEqualTo(new ApplyDecision.Stale(Long.MAX_VALUE));
        assertThat(applied(decider.decide(state(1, snapshot("S1")), update(Long.MAX_VALUE, snapshot("S2")), DecisionMode.EVENT)).heal()).isFalse();
    }

    @Test
    void equalEventVersionIsDuplicateOrConflict() {
        assertThat(decider.decide(state(7, snapshot("S1")), update(7, snapshot("S1")), DecisionMode.EVENT))
                .isEqualTo(new ApplyDecision.Duplicate());
        assertThat(decider.decide(state(7, snapshot("S1")), update(7, snapshot("S2")), DecisionMode.EVENT))
                .isEqualTo(new ApplyDecision.Conflict(7));
    }

    @Test
    void snapshotModeHealsOnlyEqualVersionDifferentContent() {
        assertThat(applied(decider.decide(state(7, snapshot("S1")), update(7, snapshot("S2")), DecisionMode.SNAPSHOT)).heal()).isTrue();
        assertThat(applied(decider.decide(state(7, snapshot("S1")), update(8, snapshot("S2")), DecisionMode.SNAPSHOT)).heal()).isFalse();
        assertThat(decider.decide(state(7, snapshot("S1")), update(7, snapshot("S1")), DecisionMode.SNAPSHOT)).isInstanceOf(ApplyDecision.Duplicate.class);
        assertThat(decider.decide(state(7, snapshot("S1")), update(6, snapshot("S2")), DecisionMode.SNAPSHOT)).isInstanceOf(ApplyDecision.Stale.class);
    }

    @Test
    void higherIdenticalVersionAppliesWithEmptyFieldsToPreserveOrdering() {
        for (DecisionMode mode : DecisionMode.values()) {
            var result = applied(decider.decide(state(7, snapshot("S1")), update(9, snapshot("S1")), mode));
            assertThat(result.changedFields()).isEmpty();
            assertThat(result.heal()).isFalse();
            assertThat(decider.decide(state(9, result.newSnapshot()), update(8, snapshot("S2")), mode)).isEqualTo(new ApplyDecision.Stale(9));
        }
    }

    @Test
    void deactivationAndReactivationHaveDistinctEvents() {
        var inactive = snapshot("S1").withStatus("INACTIVE");
        var deactivated = applied(decider.decide(state(1, snapshot("S1")), new DeactivateEmployee("E1", 2, null, null), DecisionMode.EVENT));
        assertThat(deactivated.eventType()).isEqualTo(OutboxEventType.DEACTIVATED);
        assertThat(deactivated.changedFields()).containsExactly(EmployeeField.STATUS);
        assertThat(applied(decider.decide(state(2, inactive), update(3, snapshot("S1")), DecisionMode.EVENT)).eventType()).isEqualTo(OutboxEventType.REACTIVATED);
        assertThat(applied(decider.decide(state(2, inactive), update(3, inactive), DecisionMode.EVENT)).eventType()).isEqualTo(OutboxEventType.UPDATED);
    }

    @Test
    void tombstoneRejectsLateCreateAndActiveReplacementIsCreated() {
        var current = state(5, HrSnapshot.tombstone());
        assertThat(decider.decide(current, new CreateEmployee("E1", 3, null, null, snapshot("S1")), DecisionMode.EVENT))
                .isEqualTo(new ApplyDecision.Stale(5));
        assertThat(applied(decider.decide(current, update(6, snapshot("S1")), DecisionMode.EVENT)).eventType()).isEqualTo(OutboxEventType.CREATED);
        assertThat(decider.decide(current, new DeactivateEmployee("E1", 6, null, null), DecisionMode.EVENT)).isEqualTo(new ApplyDecision.Duplicate());
        assertThat(applied(decider.decide(CurrentEmployeeState.missing(), new DeactivateEmployee("E1", 1, null, null), DecisionMode.EVENT)).newSnapshot()).isEqualTo(HrSnapshot.tombstone());
    }

    @Test
    void transportFieldsNeverEnterHrChangesEvenWhenPhoneOwnershipMoves() {
        var policy = new FieldOwnershipPolicy(Set.of(EmployeeField.PHONE, EmployeeField.PICKUP_POINT, EmployeeField.SPECIAL_ASSISTANCE));
        var current = new HrSnapshot("Test Person", "test@example.test", "+919800000001", "M1", "BLR-1", "S1", "ACTIVE", "Existing", null);
        var incoming = new HrSnapshot("Test Person", "test@example.test", "+919800000002", "M1", "BLR-1", "S2", "ACTIVE", "Ignored", "Ignored");
        var result = applied(new ChangeDecider(policy).decide(state(1, current), update(2, incoming), DecisionMode.EVENT));
        assertThat(result.changedFields()).containsExactly(EmployeeField.SHIFT_CODE);
        assertThat(result.newSnapshot().phone()).isEqualTo(current.phone());
        assertThat(result.newSnapshot().pickupPoint()).isEqualTo("Existing");
        assertThat(result.newSnapshot().specialAssistance()).isNull();
    }

    @Test
    void wireEventNamesAreStable() {
        assertThat(OutboxEventType.values()).extracting(OutboxEventType::wireName).containsExactly("EmployeeCreated", "EmployeeUpdated",
                "EmployeeDeactivated", "EmployeeReactivated", "TransportProfileUpdated", "ReconciliationBrakeTripped");
    }

    private static UpdateEmployee update(long version, HrSnapshot snapshot) { return new UpdateEmployee("E1", version, null, null, snapshot); }
    private static ApplyDecision.Apply applied(ApplyDecision decision) {
        assertThat(decision).isInstanceOf(ApplyDecision.Apply.class);
        return (ApplyDecision.Apply) decision;
    }
}
