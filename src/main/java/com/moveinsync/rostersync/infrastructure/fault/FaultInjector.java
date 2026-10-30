package com.moveinsync.rostersync.infrastructure.fault;
import com.moveinsync.rostersync.api.error.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/** Demo-instance faults are in memory, so a process restart disarms them. */
@Component
public class FaultInjector {
    private final ConcurrentMap<FaultPoint,ArmedFault> armed=new ConcurrentHashMap<>();
    private final boolean demo;
    public FaultInjector(Environment environment) { demo=environment.acceptsProfiles(Profiles.of("demo")); }
    public void arm(FaultPoint point,FaultMode mode,boolean oneShot,long delayMs) {
        if(mode==FaultMode.HALT && !demo) throw new ConflictException(ErrorCode.INVALID_STATE);
        if(delayMs<0 || delayMs>60000) throw new IllegalArgumentException("Invalid delay duration");
        armed.put(point,new ArmedFault(point,mode,oneShot,delayMs));
    }
    public List<ArmedFault> armed() {return armed.values().stream().sorted(Comparator.comparing(ArmedFault::point)).toList();}
    public void clear() {armed.clear();}
    public void hit(FaultPoint point) {
        var selected=new java.util.concurrent.atomic.AtomicReference<ArmedFault>();
        armed.computeIfPresent(point,(key,fault)->{selected.set(fault);return fault.oneShot()?null:fault;});
        var fault=selected.get(); if(fault==null) return;
        switch(fault.mode()) {
            case THROW -> throw new SimulatedCrashException();
            case HALT -> Runtime.getRuntime().halt(137);
            case DELAY_MS -> {
                try {TimeUnit.MILLISECONDS.sleep(fault.delayMs());}
                catch(InterruptedException interrupted) {Thread.currentThread().interrupt();throw new SimulatedCrashException();}
            }
        }
    }
    public record ArmedFault(FaultPoint point,FaultMode mode,boolean oneShot,long delayMs) { }
}
