package com.moveinsync.rostersync.infrastructure.fault;
import org.springframework.stereotype.Component;

/** Inert in this phase. Tests replace it with a controlled throw/gate; demo arming arrives in Phase 5. */
@Component
public class FaultInjector {
    public void hit(FaultPoint point) { }
}
