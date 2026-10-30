package com.moveinsync.rostersync.config;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration(proxyBeanMethods=false)
public class ReconciliationConfiguration {
    @Bean("reconExecutor") ThreadPoolTaskExecutor reconExecutor(RosterProperties properties) {
        var executor=new ThreadPoolTaskExecutor();executor.setCorePoolSize(1);executor.setMaxPoolSize(1);
        executor.setQueueCapacity(properties.reconciliation().queueCapacity());executor.setThreadNamePrefix("recon-worker-");
        executor.setWaitForTasksToCompleteOnShutdown(false);executor.setAwaitTerminationSeconds(20);return executor;
    }
}
