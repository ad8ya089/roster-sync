package com.moveinsync.rostersync.infrastructure.fault;
public final class SimulatedCrashException extends RuntimeException {
    public SimulatedCrashException() { super("SIMULATED_CRASH"); }
}
