package com.moveinsync.management;

import com.moveinsync.rostersync.api.error.CorrelationIdFilter;
import org.springframework.boot.actuate.autoconfigure.web.*;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

// Outside the application's component scan: this configuration belongs ONLY to Boot's
// separate management child context, loaded through ManagementContextConfiguration.imports.
@ManagementContextConfiguration(value=ManagementContextType.CHILD,proxyBeanMethods=false)
public class ManagementSecurityConfiguration {
    @Bean SecurityFilterChain managementSecurity(HttpSecurity http) throws Exception {
        return http.csrf(AbstractHttpConfigurer::disable).formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable).logout(AbstractHttpConfigurer::disable).requestCache(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(access->access.anyRequest().permitAll()).build();
    }
    @Bean FilterRegistrationBean<CorrelationIdFilter> managementCorrelationFilter(){
        var registration=new FilterRegistrationBean<>(new CorrelationIdFilter());registration.setOrder(Ordered.HIGHEST_PRECEDENCE);return registration;
    }
}
