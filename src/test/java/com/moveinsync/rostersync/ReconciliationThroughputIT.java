package com.moveinsync.rostersync;

import com.moveinsync.rostersync.application.recon.ReconciliationChunkApplier;
import com.moveinsync.rostersync.domain.model.HrSnapshot;
import com.moveinsync.rostersync.domain.recon.*;
import com.moveinsync.rostersync.infrastructure.persistence.ReconActionDao;
import java.lang.reflect.*;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import static org.assertj.core.api.Assertions.*;

/** Opt-in local comparison, not a timing gate on shared CI. Counts JDBC requests, not packets. */
@Tag("benchmark") @Timeout(180)
@Import(ReconciliationThroughputIT.ProfilingConfiguration.class)
class ReconciliationThroughputIT extends AbstractReconciliationIntegrationTest {
    private static final Probe PROBE=new Probe();
    @Autowired ReconActionDao actionDao; @Autowired ReconciliationChunkApplier chunks;
    @Autowired com.moveinsync.rostersync.config.RosterProperties properties;
    @Test void profileOneChunkAndTheFullApplyLoop() throws Exception {
        int warmup=Integer.getInteger("profile.warmup",1000),measured=Integer.getInteger("profile.actions",5000);
        seed.seed(JwtTestTokens.ACME,warmup+measured);
        UUID run=UUID.randomUUID();Path file=Files.createTempFile(temporary,"profile-",".csv");
        runs.create(JwtTestTokens.ACME,run,file,0,Instant.now(),null,false,"fixture");
        var plan=jdbc.query("SELECT e.external_id,e.name,e.email,e.phone,o.code FROM employee e JOIN office o ON o.id=e.office_id WHERE e.tenant_id=? AND e.external_id LIKE 'S%' ORDER BY e.external_id",
                (row,n)->new PlannedAction(row.getString(1),ActionType.UPDATE,2L,1L,new HrSnapshot(row.getString(2),row.getString(3),row.getString(4),null,row.getString(5),"S2","ACTIVE")),JwtTestTokens.ACME);
        actionDao.insert(JwtTestTokens.ACME,run,plan);runs.transition(JwtTestTokens.ACME,run,"UPLOADED","DIFFING");runs.transition(JwtTestTokens.ACME,run,"DIFFING","APPLYING");
        int size=Integer.getInteger("profile.chunk",properties.reconciliation().chunkSize()),processed=0;long coldMax=0;
        for(int warmed=0;warmed<warmup;){long start=System.nanoTime();warmed+=chunks.applyNext(JwtTestTokens.ACME,run,Math.min(size,warmup-warmed),"ALL");coldMax=Math.max(coldMax,System.nanoTime()-start);}
        long longest=0,firstNanos=0,start=System.nanoTime();Map<String,Long> firstQueries=null;
        try {
            do {
                PROBE.reset();PROBE.enabled.set(true);long chunkStart=System.nanoTime();int count;
                try {count=chunks.applyNext(JwtTestTokens.ACME,run,size,"ALL");}finally{PROBE.enabled.set(false);}
                long elapsed=System.nanoTime()-chunkStart;
                if(firstQueries==null){firstQueries=PROBE.snapshot();firstNanos=elapsed;}
                if(count==0)break;processed+=count;longest=Math.max(longest,elapsed);
            }while(processed<measured);
        }finally{PROBE.enabled.set(false);}
        long total=System.nanoTime()-start;
        assertThat(processed).isEqualTo(measured);assertThat(employeeOutbox()).isEqualTo(warmup+measured);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM employee WHERE external_id LIKE 'S%' AND hr_version=2 AND shift_code='S2'",Long.class)).isEqualTo(warmup+measured);
        var result=new LinkedHashMap<String,Object>();result.put("label",System.getProperty("profile.label","current"));result.put("actions",processed);result.put("chunkSize",size);
        result.put("elapsedMs",total/1_000_000.0);result.put("actionsPerSecond",processed*1_000_000_000.0/total);result.put("maxChunkMs",longest/1_000_000.0);
        result.put("warmupActions",warmup);result.put("coldStartMaxChunkMs",coldMax/1_000_000.0);
        result.put("firstChunkMs",firstNanos/1_000_000.0);result.put("firstChunkJdbcRequests",firstQueries);result.put("firstChunkRequestsPerAction",firstQueries.values().stream().mapToLong(Long::longValue).sum()/(double)Math.min(size,processed));
        Files.writeString(Path.of("target/phase8-throughput-"+System.getProperty("profile.label","current")+".json"),mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result));
        System.out.println("RECON_THROUGHPUT "+mapper.writeValueAsString(result));
    }
    static final class Probe {
        final AtomicBoolean enabled=new AtomicBoolean();final Map<String,AtomicLong> requests=new ConcurrentHashMap<>();
        void reset(){requests.clear();}void record(String sql){if(enabled.get())requests.computeIfAbsent(sql.replaceAll("\\s+"," ").strip(),key->new AtomicLong()).incrementAndGet();}
        Map<String,Long> snapshot(){var result=new TreeMap<String,Long>();requests.forEach((sql,count)->result.put(sql,count.get()));return result;}
        Connection connection(Connection delegate){return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
            if(method.getName().equals("commit"))record("COMMIT");
            Object value=invoke(delegate,method,args);
            if(method.getName().equals("prepareStatement") && value instanceof PreparedStatement statement){String sql=(String)args[0];
                return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),new Class<?>[]{PreparedStatement.class},(ignored,operation,parameters)->{
                    if(operation.getName().startsWith("execute"))record(sql+(operation.getName().contains("Batch")?" [JDBC batch]":""));return invoke(statement,operation,parameters);});}
            return value;
        });}
        static Object invoke(Object target,Method method,Object[] args) throws Throwable {try{return method.invoke(target,args);}catch(InvocationTargetException failure){throw failure.getCause();}}
    }
    @TestConfiguration(proxyBeanMethods=false) static class ProfilingConfiguration {
        @Bean static BeanPostProcessor countJdbcRequests(){return new BeanPostProcessor(){@Override public Object postProcessAfterInitialization(Object bean,String name){
            if(!(bean instanceof DataSource source))return bean;return new CountedDataSource(source);
        }};}
    }
    static final class CountedDataSource extends DelegatingDataSource implements AutoCloseable {
        CountedDataSource(DataSource source){super(source);}
        @Override public Connection getConnection() throws SQLException{return PROBE.connection(super.getConnection());}
        @Override public Connection getConnection(String username,String password) throws SQLException{return PROBE.connection(super.getConnection(username,password));}
        @Override public void close() throws Exception {if(getTargetDataSource() instanceof AutoCloseable closeable)closeable.close();}
    }
}
