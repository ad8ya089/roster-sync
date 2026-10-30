package com.moveinsync.rostersync.application.recon;
import com.moveinsync.rostersync.config.RosterProperties;
import com.moveinsync.rostersync.domain.recon.BucketPartitioner;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;
import java.io.Reader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Service
public class DemoRosterSeeder {
    private final JdbcTemplate jdbc;private final Clock clock;private final int buckets;private final TransactionTemplate transactions;
    public DemoRosterSeeder(JdbcTemplate jdbc,Clock clock,RosterProperties properties,PlatformTransactionManager manager){this.jdbc=jdbc;this.clock=clock;buckets=properties.reconciliation().buckets();transactions=new TransactionTemplate(manager);}
    public Map<String,Object> seed(UUID tenantId,int count) {
        long started=System.nanoTime();var offices=jdbc.queryForList("SELECT id FROM office WHERE tenant_id=? AND active ORDER BY code",Long.class,tenantId);
        if(offices.isEmpty()) throw new IllegalStateException("No demo offices");
        var now=clock.instant();
        // Demo fixture generation intentionally bypasses outbox/receipts. These are initial snapshots,
        // not source events. Java CRC32 is authoritative; PostgreSQL has no matching built-in CRC32.
        String columns="tenant_id,external_id,name,email,phone,office_id,shift_code,status,hr_version,hr_updated_at,recon_bucket,created_at,updated_at";
        transactions.executeWithoutResult(status->{
            jdbc.execute("CREATE TEMP TABLE roster_seed_stage ON COMMIT DROP AS SELECT "+columns+" FROM employee WITH NO DATA");
            jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Long>)connection->{
                try(var input=new Reader(){
                    int next=1,position;String line="";
                    public int read(char[] destination,int offset,int length) {
                        if(length==0)return 0;int copied=0;
                        while(copied<length) {
                            if(position==line.length()) {
                                if(next>count)break;int index=next++;String digits=Integer.toString(index);String id="S"+"0".repeat(Math.max(0,6-digits.length()))+digits;
                                line=tenantId+"\t"+id+"\tSeed Employee "+index+"\t"+id.toLowerCase(Locale.ROOT)+"@seed.test\t+919800000001\t"+offices.get((index-1)%offices.size())+
                                        "\tS1\tACTIVE\t1\t"+now+"\t"+BucketPartitioner.bucketOf(id,buckets)+"\t"+now+"\t"+now+"\n";position=0;
                            }
                            int take=Math.min(length-copied,line.length()-position);line.getChars(position,position+take,destination,offset+copied);position+=take;copied+=take;
                        }
                        return copied==0?-1:copied;
                    }
                    public void close(){ }
                }) {return connection.unwrap(org.postgresql.PGConnection.class).getCopyAPI().copyIn("COPY roster_seed_stage ("+columns+") FROM STDIN",input);}
                catch(java.io.IOException failure){throw new IllegalStateException("SEED_IO_FAILED");}
            });
            jdbc.update("INSERT INTO employee ("+columns+") SELECT "+columns+" FROM roster_seed_stage ON CONFLICT (tenant_id,external_id) DO NOTHING");
        });
        long elapsed=(System.nanoTime()-started)/1000000;
        org.slf4j.LoggerFactory.getLogger(DemoRosterSeeder.class).info("recon.demo.seeded tenantId={} count={} elapsedMs={}",tenantId,count,elapsed);
        return Map.of("requested",count,"elapsedMs",elapsed);
    }
}
