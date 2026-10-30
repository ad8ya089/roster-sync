package com.moveinsync.rostersync.application.outbox;
import java.util.UUID;

public record OutboxEventView(long id, UUID eventId, UUID tenantId, String aggregateId,
                              Long aggregateVersion, String eventType, String payload, int attempts) {
    @Override public String toString() { return "OutboxEventView[eventId="+eventId+", type="+eventType+"]"; }
}
