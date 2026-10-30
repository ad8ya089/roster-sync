package com.moveinsync.rostersync.config;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class SinkExecutorShutdown {
    private final ExecutorService executor;
    public SinkExecutorShutdown(@Qualifier("sinkExecutor") ExecutorService executor) { this.executor=executor; }
    @PreDestroy public void drain() {
        executor.shutdown();
        try { if(!executor.awaitTermination(20,TimeUnit.SECONDS)) executor.shutdownNow(); }
        catch(InterruptedException interrupted) { executor.shutdownNow(); Thread.currentThread().interrupt(); }
    }
}
