package com.moveinsync.rostersync.infrastructure.cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.moveinsync.rostersync.config.RosterProperties;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods=false) @EnableCaching
public class CacheConfig {
    @Bean CacheManager cacheManager(RosterProperties properties){
        var manager=new CaffeineCacheManager("offices","employeeView");manager.setAllowNullValues(false);
        var offices=properties.cache().offices();var employees=properties.cache().employeeView();
        manager.registerCustomCache("offices",Caffeine.newBuilder().expireAfterWrite(offices.ttl()).maximumSize(offices.maximumSize()).recordStats().build());
        manager.registerCustomCache("employeeView",Caffeine.newBuilder().expireAfterWrite(employees.ttl()).maximumSize(employees.maximumSize()).recordStats().build());
        return manager;
    }
    public record OfficeKey(java.util.UUID tenantId,String code) { }
    public record EmployeeKey(java.util.UUID tenantId,String employeeId) { }
    public record OfficeRef(Long id,String code,boolean active) { }
}
