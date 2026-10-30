package com.moveinsync.rostersync.application.recon;
import com.moveinsync.rostersync.domain.model.HrSnapshot;
import com.moveinsync.rostersync.infrastructure.csv.RosterCsvReader;
import java.io.*;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.apache.commons.csv.*;
import org.springframework.stereotype.Component;

/** Streaming generator shared by dev export, integration tests and the later benchmark. */
@Component
public class RosterFileGenerator {
    public record Spec(Long truncateAt,BigDecimal mutatePercent,BigDecimal dropPercent,BigDecimal newPercent,long seed) {
        public Spec {Objects.requireNonNull(mutatePercent);Objects.requireNonNull(dropPercent);Objects.requireNonNull(newPercent);}
        public static Spec unchanged(){return new Spec(null,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,42);}
    }
    public record Expected(long existing,long dropped,long updates,long creates,long written) { }
    @FunctionalInterface public interface Source {void stream(RowConsumer consumer) throws IOException;}
    @FunctionalInterface public interface RowConsumer {void accept(String id,long version,HrSnapshot snapshot) throws IOException;}
    public Session open(OutputStream output,Spec spec) throws IOException {return new Session(output,spec);}
    public static final class Session implements AutoCloseable {
        private final CSVPrinter printer;private final Spec spec;private long existing,dropped,updated,created,written;private boolean truncated;
        private String firstOffice="BLR-1";
        Session(OutputStream output,Spec spec) throws IOException {
            this.spec=spec;printer=new CSVPrinter(new BufferedWriter(new OutputStreamWriter(output,StandardCharsets.UTF_8)),CSVFormat.DEFAULT);
            printer.printRecord(RosterCsvReader.COLUMNS);
        }
        public void accept(String id,long version,HrSnapshot snapshot) throws IOException {
            existing++;if(snapshot.officeCode()!=null) firstOffice=snapshot.officeCode();
            // Separate deterministic lanes; drop takes precedence, mutation selects survivors.
            if(selected(id,spec.seed(),spec.dropPercent())){dropped++;return;}
            if(spec.truncateAt()!=null && written>=spec.truncateAt()){truncated=true;return;}
            boolean mutate=selected(id,spec.seed()+1,spec.mutatePercent());
            if(mutate){updated++;version=Math.addExact(version,1);snapshot=new HrSnapshot(snapshot.name(),snapshot.email(),snapshot.phone(),snapshot.managerId(),snapshot.officeCode(),"S2".equals(snapshot.shiftCode())?"S1":"S2",snapshot.status());}
            print(id,version,snapshot);written++;
        }
        public Expected finish() throws IOException {
            long additions=BigDecimal.valueOf(existing).multiply(spec.newPercent()).divide(new BigDecimal("100"),0,RoundingMode.DOWN).longValueExact();
            for(long index=1;index<=additions;index++) {
                if(spec.truncateAt()!=null && written>=spec.truncateAt()){truncated=true;break;}
                String id="N"+Long.toUnsignedString(spec.seed())+"_"+index;
                print(id,1,new HrSnapshot("New Fixture "+index,"new"+index+"@seed.test","+919800000001",null,firstOffice,"S1","ACTIVE"));created++;written++;
            }
            if(!truncated && spec.truncateAt()==null) printer.printRecord("#TRAILER","row_count="+written);
            printer.flush();return new Expected(existing,dropped,updated,created,written);
        }
        private void print(String id,long version,HrSnapshot row) throws IOException {printer.printRecord(id,version,row.name(),row.email(),row.phone(),row.managerId(),row.officeCode(),row.shiftCode(),row.status());}
        public void close() throws IOException {printer.flush();}
    }
    private static boolean selected(String id,long seed,BigDecimal percent) {
        if(percent.signum()==0)return false;
        var hash=new java.util.zip.CRC32();hash.update((seed+":"+id).getBytes(StandardCharsets.UTF_8));
        return BigDecimal.valueOf(hash.getValue()%1000000).compareTo(percent.multiply(new BigDecimal("10000")))<0;
    }
}
