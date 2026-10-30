package com.moveinsync.rostersync.application.recon;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;

@Component
public class ReconWorkQueue {
    private final TaskExecutor executor;private final Set<UUID> scheduled=ConcurrentHashMap.newKeySet();
    private final Map<UUID,Runnable> following=new HashMap<>();
    private final com.moveinsync.rostersync.infrastructure.metrics.MdcTaskDecorator decorator;
    public ReconWorkQueue(@Qualifier("reconExecutor") TaskExecutor executor,com.moveinsync.rostersync.infrastructure.metrics.MdcTaskDecorator decorator){this.executor=executor;this.decorator=decorator;}
    public synchronized void submit(UUID runId,Runnable job) {
        // An approval can arrive immediately after the diff CAS while that worker is still
        // cleaning its spill directory. Preserve one follow-up instead of losing the apply job.
        if(!scheduled.add(runId)) {following.put(runId,decorator.decorate(job));return;}
        try{executor.execute(()->{try{job.run();}finally{synchronized(this){scheduled.remove(runId);var next=following.remove(runId);if(next!=null)submit(runId,next);}}});}
        catch(RuntimeException rejected){scheduled.remove(runId);throw rejected;}
    }
    public boolean active(UUID runId){return scheduled.contains(runId);}
    public boolean idle(){return scheduled.isEmpty();}
}
