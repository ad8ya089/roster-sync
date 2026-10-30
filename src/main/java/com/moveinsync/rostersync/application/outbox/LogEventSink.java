package com.moveinsync.rostersync.application.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component @Order(100)
public class LogEventSink implements EventSink {
    private static final Logger LOG=LoggerFactory.getLogger(LogEventSink.class);
    public String name() { return "log"; }
    public boolean supports(String eventType) { return true; }
    public void handle(OutboxEventView event) {
        LOG.info("outbox.published eventId={} tenantId={} employeeId={} version={} eventType={}",
                event.eventId(),event.tenantId(),event.aggregateId(),event.aggregateVersion(),event.eventType());
    }
}
