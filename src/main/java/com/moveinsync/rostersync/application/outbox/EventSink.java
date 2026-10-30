package com.moveinsync.rostersync.application.outbox;
public interface EventSink {
    String name();
    boolean supports(String eventType);
    void handle(OutboxEventView event);
}
