package com.moveinsync.rostersync.domain.outbox;

import java.time.Duration;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class BackoffPolicyTest {
    @Test
    void doublesFromOneSecondAndCapsAtFiveMinutesEvenForHugeAttempts() {
        var policy = new BackoffPolicy(10);
        long[] seconds = {1, 2, 4, 8, 16, 32, 64, 128, 256, 300, 300};
        for (int index = 0; index < seconds.length; index++) assertThat(policy.delay(index + 1)).isEqualTo(Duration.ofSeconds(seconds[index]));
        assertThat(policy.delay(Integer.MAX_VALUE)).isEqualTo(Duration.ofSeconds(300));
        assertThat(policy.dead(9)).isFalse(); assertThat(policy.dead(10)).isTrue(); assertThat(policy.dead(11)).isTrue();
    }

    @Test
    void acceptsConfigurationAndRejectsInvalidSettings() {
        var policy = new BackoffPolicy(Duration.ofMillis(100), Duration.ofMillis(300), 3);
        assertThat(policy.delay(1)).isEqualTo(Duration.ofMillis(100));
        assertThat(policy.delay(2)).isEqualTo(Duration.ofMillis(200));
        assertThat(policy.delay(3)).isEqualTo(Duration.ofMillis(300));
        assertThat(new BackoffPolicy(Duration.ofSeconds(1), Duration.ofSeconds(1), 1).delay(2)).isEqualTo(Duration.ofSeconds(1));
        assertThatIllegalArgumentException().isThrownBy(() -> policy.delay(0));
        assertThatIllegalArgumentException().isThrownBy(() -> new BackoffPolicy(0));
        assertThatIllegalArgumentException().isThrownBy(() -> new BackoffPolicy(Duration.ZERO, Duration.ofSeconds(1), 1));
        assertThatIllegalArgumentException().isThrownBy(() -> new BackoffPolicy(Duration.ofSeconds(-1), Duration.ofSeconds(1), 1));
        assertThatIllegalArgumentException().isThrownBy(() -> new BackoffPolicy(Duration.ofSeconds(2), Duration.ofSeconds(1), 1));
        assertThatNullPointerException().isThrownBy(() -> new BackoffPolicy(null, Duration.ofSeconds(1), 1));
        assertThatNullPointerException().isThrownBy(() -> new BackoffPolicy(Duration.ofSeconds(1), null, 1));
    }
}
