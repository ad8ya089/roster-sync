package com.moveinsync.rostersync.domain.command;

import com.moveinsync.rostersync.domain.model.HrSnapshot;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static com.moveinsync.rostersync.domain.DomainFixtures.*;
import static org.assertj.core.api.Assertions.*;

class HrCommandTest {
    @Test
    void createDefaultsStatusAndExposesOnlySafeMetadata() {
        var command = new CreateEmployee("E1", 1, "event-1", NOW, snapshot("S1").withStatus(null));
        assertThat(command.resolveSnapshot(Optional.empty()).status()).isEqualTo("ACTIVE");
        assertThat(command.type()).isEqualTo(HrEventType.CREATE);
        assertThat(command.employeeId()).isEqualTo("E1");
        assertThat(command.version()).isEqualTo(1);
        assertThat(command.eventId()).isEqualTo("event-1");
        assertThat(command.occurredAt()).isEqualTo(NOW);
        assertThat(command.toString()).doesNotContain("Test Person", "test@example.test");
        assertThat(new CreateEmployee("E1", 2, null, null, snapshot("S1").withStatus("INACTIVE"))
                .resolveSnapshot(Optional.empty()).status()).isEqualTo("INACTIVE");
    }

    @Test
    void updatePreservesRequiredStatusForBoundaryValidation() {
        var command = new UpdateEmployee("E1", 2, null, null, snapshot("S1").withStatus(null));
        assertThat(command.resolveSnapshot(Optional.empty()).status()).isNull();
        assertThat(command.type()).isEqualTo(HrEventType.UPDATE);
        assertThatNullPointerException().isThrownBy(() -> new UpdateEmployee("E1", 1, null, null, null));
        assertThatNullPointerException().isThrownBy(() -> new CreateEmployee("E1", 1, null, null, null));
    }

    @Test
    void deactivateCarriesExistingFieldsAndCreatesMissingTombstone() {
        var command = new DeactivateEmployee("E1", 3, null, null);
        HrSnapshot existing = snapshot("S1");
        assertThat(command.resolveSnapshot(Optional.of(existing))).isEqualTo(existing.withStatus("INACTIVE"));
        assertThat(command.resolveSnapshot(Optional.empty())).isEqualTo(HrSnapshot.tombstone());
        assertThat(command.type()).isEqualTo(HrEventType.DEACTIVATE);
    }
}
