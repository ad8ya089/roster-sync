package com.moveinsync.rostersync.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ApplicationConfiguration {
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
