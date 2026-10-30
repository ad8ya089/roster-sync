package com.moveinsync.rostersync;

import com.moveinsync.rostersync.application.outbox.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public class RecordingEventSink implements EventSink {
    private final ConcurrentMap<UUID,AtomicInteger> deliveries=new ConcurrentHashMap<>();
    private volatile Consumer<OutboxEventView> action=event->{ };
    public String name() {return "recording";}
    public boolean supports(String type) {return true;}
    public void handle(OutboxEventView event) {action.accept(event);deliveries.computeIfAbsent(event.eventId(),id->new AtomicInteger()).incrementAndGet();}
    public void action(Consumer<OutboxEventView> action) {this.action=action;}
    public Map<UUID,Integer> deliveries() {var copy=new HashMap<UUID,Integer>();deliveries.forEach((id,count)->copy.put(id,count.get()));return copy;}
    public void reset() {deliveries.clear();action=event->{ };}
}
