package com.moveinsync.rostersync;

import java.time.*;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;

@TestConfiguration(proxyBeanMethods=false)
public class OutboxTestConfiguration {
    @Bean @Order(-100) RecordingEventSink recordingEventSink() {return new RecordingEventSink();}
    @Bean @Primary MutableClock mutableClock() {return new MutableClock();}
    public static class MutableClock extends Clock {
        private final AtomicReference<Instant> now=new AtomicReference<>(Instant.parse("2026-10-30T10:00:00Z"));
        public ZoneId getZone() {return ZoneOffset.UTC;}
        public Clock withZone(ZoneId zone) {return Clock.fixed(instant(),zone);}
        public Instant instant() {return now.get();}
        public void advance(Duration duration) {now.updateAndGet(value->value.plus(duration));}
        public void reset() {now.set(Instant.parse("2026-10-30T10:00:00Z"));}
    }
}
