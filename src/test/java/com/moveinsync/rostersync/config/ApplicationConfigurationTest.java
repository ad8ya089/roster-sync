package com.moveinsync.rostersync.config;

import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicationConfigurationTest {
    @Test
    void suppliesUtcClockForApplicationTime() {
        assertThat(new ApplicationConfiguration().clock().getZone()).isEqualTo(ZoneOffset.UTC);
    }
}
