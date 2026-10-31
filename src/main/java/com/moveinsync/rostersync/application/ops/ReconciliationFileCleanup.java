package com.moveinsync.rostersync.application.ops;

import com.moveinsync.rostersync.application.recon.ReconWorkQueue;
import com.moveinsync.rostersync.config.RosterProperties;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.slf4j.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class ReconciliationFileCleanup {
    private static final Logger LOG=LoggerFactory.getLogger(ReconciliationFileCleanup.class);
    private static final Set<String> TERMINAL=Set.of("COMPLETED","REJECTED","FAILED","PLANNED");
    private final JdbcClient jdbc;
    private final Path root;
    private final Clock clock;
    private final ReconWorkQueue queue;

    public ReconciliationFileCleanup(JdbcClient jdbc,RosterProperties properties,Clock clock,ReconWorkQueue queue) {
        this.jdbc=jdbc;this.clock=clock;this.queue=queue;
        root=properties.reconciliation().dataDir().toAbsolutePath().normalize().resolve("recon");
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public int expiredUploads(Instant cutoff) {
        // Global maintenance inventory. Keep each DB/file batch short and retain run audit rows.
        var files=jdbc.sql("""
                SELECT id,tenant_id,file_path FROM recon_run
                WHERE status IN ('COMPLETED','REJECTED','FAILED','PLANNED') AND finished_at<? AND file_path<>'deleted'
                ORDER BY finished_at,id LIMIT 500 FOR UPDATE SKIP LOCKED
                """).param(Timestamp.from(cutoff)).query((row,n)->new Upload(row.getObject(1,UUID.class),row.getObject(2,UUID.class),row.getString(3))).list();
        int removed=0;
        for(var file:files) {
            if(queue.active(file.id())) continue;
            Path expected=root.resolve(file.id()+".csv");
            // Never act on a database path outside our generated upload namespace.
            if(!Path.of(file.path()).toAbsolutePath().normalize().equals(expected) || !safeRoot()) continue;
            try {
                Files.deleteIfExists(expected);
                // A crash after unlink but before commit is safe: deleteIfExists retries idempotently.
                jdbc.sql("UPDATE recon_run SET file_path='deleted' WHERE tenant_id=? AND id=?")
                        .params(file.tenantId(),file.id()).update();removed++;
            } catch(IOException failure) {warn(file.id(),failure);}
        }
        return removed;
    }

    public void abandoned() {
        if(!safeRoot()) return;
        Instant cutoff=clock.instant().minus(Duration.ofDays(1));
        try(var entries=Files.list(root)) {
            for(var path:(Iterable<Path>)entries::iterator) {
                boolean directory=Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS);
                String name=path.getFileName().toString();
                if(!directory && !name.endsWith(".csv")) continue;
                UUID id;
                try{id=UUID.fromString(directory?name:name.substring(0,name.length()-4));}
                catch(IllegalArgumentException ignored){continue;}
                if(!name.equals(directory?id.toString():id+".csv") || queue.active(id) || Files.isSymbolicLink(path)) continue;
                try {
                    if(!Files.getLastModifiedTime(path,LinkOption.NOFOLLOW_LINKS).toInstant().isBefore(cutoff)) continue;
                    var status=jdbc.sql("SELECT status FROM recon_run WHERE id=?").param(id).query(String.class).optional();
                    // Conservative protection: ALL live/awaiting runs retain files, not just DIFFING.
                    if(status.isPresent() && (!directory || !TERMINAL.contains(status.get()))) continue;
                    deleteGenerated(path,id,directory);
                } catch(IOException failure) {warn(id,failure);}
            }
        } catch(IOException failure) {LOG.warn("recon.cleanup.failed exceptionClass={}",failure.getClass().getSimpleName());}
    }

    private boolean safeRoot() {return !Files.isSymbolicLink(root) && Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS);}
    private void deleteGenerated(Path path,UUID id,boolean directory) throws IOException {
        Path expected=root.resolve(directory?id.toString():id+".csv").normalize();
        if(!path.toAbsolutePath().normalize().equals(expected) || !expected.startsWith(root) || expected.equals(root)) return;
        if(!directory){Files.deleteIfExists(expected);return;}
        // No FOLLOW_LINKS: nested links are unlinked, never traversed outside the generated directory.
        Files.walkFileTree(expected,new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file,BasicFileAttributes attributes) throws IOException {
                Files.delete(file);return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path directory,IOException error) throws IOException {
                if(error!=null)throw error;Files.delete(directory);return FileVisitResult.CONTINUE;
            }
        });
    }
    private static void warn(UUID id,IOException failure) {LOG.warn("recon.cleanup.failed runId={} exceptionClass={}",id,failure.getClass().getSimpleName());}
    private record Upload(UUID id,UUID tenantId,String path) { }
}
