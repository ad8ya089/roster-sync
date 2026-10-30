package com.moveinsync.rostersync.infrastructure.fault;

import com.moveinsync.rostersync.api.error.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;

class FaultInjectorTest {
    @Test void haltIsRefusedOutsideDemoWithoutArmingIt() {
        var faults=new FaultInjector(new MockEnvironment());
        assertThatThrownBy(()->faults.arm(FaultPoint.OUTBOX_BEFORE_DISPATCH,FaultMode.HALT,true,0))
                .isInstanceOfSatisfying(ConflictException.class,error->assertThat(error.errorCode()).isEqualTo(ErrorCode.INVALID_STATE));
        assertThat(faults.armed()).isEmpty();
    }
    @Test void oneShotIsRemovedBeforeItThrowsAndPersistentFaultCanBeCleared() {
        var faults=new FaultInjector(new MockEnvironment());
        faults.arm(FaultPoint.OUTBOX_BEFORE_DISPATCH,FaultMode.THROW,true,0);
        assertThatThrownBy(()->faults.hit(FaultPoint.OUTBOX_BEFORE_DISPATCH)).isInstanceOf(SimulatedCrashException.class);
        faults.hit(FaultPoint.OUTBOX_BEFORE_DISPATCH);assertThat(faults.armed()).isEmpty();
        faults.arm(FaultPoint.OUTBOX_BEFORE_MARK_PUBLISHED,FaultMode.THROW,false,0);
        assertThatThrownBy(()->faults.hit(FaultPoint.OUTBOX_BEFORE_MARK_PUBLISHED)).isInstanceOf(SimulatedCrashException.class);
        assertThat(faults.armed()).hasSize(1);faults.clear();assertThat(faults.armed()).isEmpty();
    }
}
