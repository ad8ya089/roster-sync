package com.moveinsync.rostersync.config;
import com.moveinsync.rostersync.domain.outbox.BackoffPolicy;
import java.util.concurrent.*;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods=false) @EnableScheduling
public class OutboxConfiguration {
    @Bean BackoffPolicy outboxBackoff(RosterProperties properties) { return new BackoffPolicy(properties.outbox().maxAttempts()); }
    @Bean(destroyMethod="shutdown") ExecutorService sinkExecutor(com.moveinsync.rostersync.infrastructure.metrics.MdcTaskDecorator decorator) {
        return new ThreadPoolExecutor(4,4,0L,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(100),
                Thread.ofPlatform().name("outbox-sink-",0).factory(),new ThreadPoolExecutor.AbortPolicy()) {
            @Override public void execute(Runnable task){super.execute(decorator.decorate(task));}
        };
    }
    @Bean ThreadPoolTaskScheduler taskScheduler() {
        var scheduler=new ThreadPoolTaskScheduler(); scheduler.setPoolSize(1); scheduler.setThreadNamePrefix("roster-scheduler-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true); scheduler.setAwaitTerminationSeconds(20);
        scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false); scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        return scheduler;
    }
}
