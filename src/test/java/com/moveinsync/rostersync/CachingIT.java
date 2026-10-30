package com.moveinsync.rostersync;
import com.moveinsync.rostersync.application.employee.*;
import com.moveinsync.rostersync.application.ingest.EmployeeChangeApplier;
import com.moveinsync.rostersync.domain.command.UpdateEmployee;
import com.moveinsync.rostersync.domain.model.*;
import com.moveinsync.rostersync.domain.validation.OfficeLookup;
import com.moveinsync.rostersync.infrastructure.cache.*;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

@Timeout(30)
class CachingIT extends AbstractReconciliationIntegrationTest {
    @Autowired CacheManager caches;@Autowired EmployeeQueryService views;@Autowired OfficeService officeService;
    @Autowired OfficeLookup lookup;@Autowired EmployeeChangeApplier changes;@Autowired PlatformTransactionManager transactions;
    @Autowired AfterCommitCacheEvictor evictor;
    private EmployeeView view(String id){return views.get(JwtTestTokens.ACME,id,Role.TRANSPORT_ADMIN,null);}
    private com.github.benmanes.caffeine.cache.Cache<Object,Object> nativeCache(String name){return (com.github.benmanes.caffeine.cache.Cache<Object,Object>)caches.getCache(name).getNativeCache();}
    @Test void cacheHitsAndCommittedWritesAreVisibleImmediately() throws Exception {
        long hits=nativeCache("employeeView").stats().hitCount();view("E1001");view("E1001");assertThat(nativeCache("employeeView").stats().hitCount()).isEqualTo(hits+1);
        send(event("E1001",2,"UPDATE","BLR-2","S2"));assertThat(view("E1001").shiftCode()).isEqualTo("S2");
        request("PATCH","/api/v1/employees/E1001/transport-fields",jwt(Role.TRANSPORT_ADMIN),"{\"pickupPoint\":\"Gate 2\"}");assertThat(view("E1001").transport().pickupPoint()).isEqualTo("Gate 2");
        assertThat(nativeCache("offices").policy().expireAfterWrite().orElseThrow().getExpiresAfter()).isEqualTo(Duration.ofMinutes(10));
        assertThat(nativeCache("employeeView").policy().eviction().orElseThrow().getMaximum()).isEqualTo(50000);
    }
    @Test void versionAdvancedAlsoEvictsTheVisibleVersion() throws Exception {
        send(event("CACHE",1,"CREATE","BLR-1","S1"));assertThat(view("CACHE").hrVersion()).isEqualTo(1);
        assertThat(send(event("CACHE",2,"UPDATE","BLR-1","S1")).summary().noChange()).isEqualTo(1);
        assertThat(view("CACHE").hrVersion()).isEqualTo(2);
    }
    @Test void rollbackKeepsTheCachedDtoAndNoSynchronizationEvictsImmediately() throws Exception {
        send(event("ROLL",1,"CREATE","BLR-1","S1"));var before=view("ROLL");
        new TransactionTemplate(transactions).executeWithoutResult(status->{
            changes.apply(JwtTestTokens.ACME,new UpdateEmployee("ROLL",2,null,null,new HrSnapshot("Fixture Person","fixture@example.test","+919800000001",null,"BLR-1","S2","ACTIVE")),List.of());
            assertThat(view("ROLL")).isSameAs(before);status.setRollbackOnly();
        });
        assertThat(view("ROLL")).isSameAs(before);assertThat(version("ROLL")).isEqualTo(1);
        evictor.employee(JwtTestTokens.ACME,"ROLL");assertThat(nativeCache("employeeView").asMap()).doesNotContainKey(new CacheConfig.EmployeeKey(JwtTestTokens.ACME,"ROLL"));
    }
    @Test void officeMissesAreNotCachedAndTenantKeysAreSeparate() throws Exception {
        assertThat(lookup.lookup(JwtTestTokens.ACME,"NEW")).isEqualTo(OfficeLookup.Status.UNKNOWN);
        assertThat(nativeCache("offices").asMap()).doesNotContainKey(new CacheConfig.OfficeKey(JwtTestTokens.ACME,"NEW"));
        officeService.create(JwtTestTokens.ACME,"NEW","New");assertThat(lookup.lookup(JwtTestTokens.ACME,"NEW")).isEqualTo(OfficeLookup.Status.ACTIVE);
        UUID globex=UUID.fromString("00000000-0000-0000-0000-000000000002");officeService.create(globex,"NEW","Other");lookup.lookup(globex,"NEW");
        officeService.patch(JwtTestTokens.ACME,"NEW",null,false);assertThat(lookup.lookup(JwtTestTokens.ACME,"NEW")).isEqualTo(OfficeLookup.Status.INACTIVE);
        assertThat(lookup.lookup(globex,"NEW")).isEqualTo(OfficeLookup.Status.ACTIVE);
    }
    @Test void selfAuthorizationRunsBeforeAHotCacheLookup() throws Exception {
        view("E1002");assertThatThrownBy(()->views.get(JwtTestTokens.ACME,"E1002",Role.EMPLOYEE,"E1001")).isInstanceOf(com.moveinsync.rostersync.api.error.NotFoundException.class);
        UUID globex=UUID.fromString("00000000-0000-0000-0000-000000000002");
        assertThatThrownBy(()->views.get(globex,"E1002",Role.TRANSPORT_ADMIN,null)).isInstanceOf(com.moveinsync.rostersync.api.error.NotFoundException.class);
    }
    @Test void reconciliationChangesEvictAllActionTypesAndSeedClearsTheCache() throws Exception {
        for(int index=1;index<=5;index++)view("E100"+index);
        var lines=new ArrayList<String>();for(String line:export(com.moveinsync.rostersync.application.recon.RosterFileGenerator.Spec.unchanged()).lines().toList()) {
            if(line.startsWith("#TRAILER") || line.startsWith("\"#TRAILER\"") || line.startsWith("E1003,"))continue;
            if(line.startsWith("E100")){var values=line.split(",",-1);if(values[0].equals("E1001"))values[7]="S2";if(values[0].equals("E1002"))values[1]="2";if(values[0].equals("E1004")){values[1]="2";values[7]="S2";}line=String.join(",",values);}lines.add(line);
        }
        var run=finished(upload(String.join("\n",lines)+"\n"+row("CACHE_NEW",1,"S1"),false));assertThat(run.status()).isEqualTo("COMPLETED");
        assertThat(run.plan().heals()).isEqualTo(1);assertThat(run.plan().advances()).isEqualTo(1);assertThat(run.plan().deactivations()).isEqualTo(1);
        assertThat(view("E1001").shiftCode()).isEqualTo("S2");assertThat(view("E1002").hrVersion()).isEqualTo(2);assertThat(view("E1003").status()).isEqualTo("INACTIVE");assertThat(view("E1004").hrVersion()).isEqualTo(2);assertThat(view("CACHE_NEW").hrVersion()).isEqualTo(1);
        seed.seed(JwtTestTokens.ACME,2);assertThat(nativeCache("employeeView").asMap()).isEmpty();
    }
    @Test void settingsAreNotCachedForTheNextRun() throws Exception {
        settings.get(JwtTestTokens.ACME);settings.set(JwtTestTokens.ACME,new BigDecimal("0.5"),null,"fixture");
        String csv=String.join("\n",export(com.moveinsync.rostersync.application.recon.RosterFileGenerator.Spec.unchanged()).lines().filter(line->!line.startsWith("E1004,") && !line.startsWith("E1005,") && !line.contains("#TRAILER")).toList())+"\n";
        assertThat(finished(upload(csv,false)).status()).isEqualTo("COMPLETED");
    }
}
