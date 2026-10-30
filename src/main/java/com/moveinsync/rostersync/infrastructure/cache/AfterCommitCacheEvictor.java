package com.moveinsync.rostersync.infrastructure.cache;
import java.util.UUID;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.*;

@Component
public class AfterCommitCacheEvictor {
    private final CacheManager caches;
    public AfterCommitCacheEvictor(CacheManager caches){this.caches=caches;}
    public void employee(UUID tenantId,String id){after(()->caches.getCache("employeeView").evict(new CacheConfig.EmployeeKey(tenantId,id)));}
    public void office(UUID tenantId,String code){after(()->caches.getCache("offices").evict(new CacheConfig.OfficeKey(tenantId,code)));}
    public void allEmployees(){after(()->caches.getCache("employeeView").clear());}
    private static void after(Runnable eviction){
        if(!TransactionSynchronizationManager.isSynchronizationActive()){eviction.run();return;}
        // No before-commit eviction: rollback keeps the cached value, and a concurrent reader
        // cannot refill an old value between our invalidation and this transaction's commit.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCommit(){eviction.run();}});
    }
}
