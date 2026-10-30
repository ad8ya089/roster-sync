package com.moveinsync.rostersync.application.recon;
import com.moveinsync.rostersync.config.RosterProperties;
import java.io.*;
import java.nio.file.*;
import java.util.UUID;
import org.slf4j.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Component
public class ReconciliationInboxPoller {
    private static final Logger LOG=LoggerFactory.getLogger(ReconciliationInboxPoller.class);
    private final Path inbox;private final JdbcClient jdbc;private final ReconciliationUploadService uploads;
    public ReconciliationInboxPoller(RosterProperties properties,JdbcClient jdbc,ReconciliationUploadService uploads){inbox=properties.reconciliation().inboxDir();this.jdbc=jdbc;this.uploads=uploads;}
    @Scheduled(cron="${roster.reconciliation.inbox-cron}")
    public void poll() {
        if(inbox==null) return;
        var root=inbox.toAbsolutePath().normalize();
        var tenants=jdbc.sql("SELECT id,code FROM tenant ORDER BY code").query((row,n)->new Tenant(row.getObject(1,UUID.class),row.getString(2))).list();
        for(var tenant:tenants) {
            Path folder=root.resolve(tenant.code()).normalize();if(!folder.startsWith(root) || !Files.isDirectory(folder))continue;
            try(var files=Files.list(folder)) {
                for(var file:files.filter(path->path.getFileName().toString().endsWith(".csv")).sorted().toList()) {
                    try{uploads.upload(tenant.id(),"folder-poller",new FileUpload(file),Files.getLastModifiedTime(file).toInstant(),null,false);}
                    catch(Exception unavailable){LOG.warn("recon.inbox.not_accepted tenantId={} exceptionClass={}",tenant.id(),unavailable.getClass().getSimpleName());}
                }
            }catch(IOException unavailable){LOG.warn("recon.inbox.unavailable tenantId={}",tenant.id());}
        }
    }
    private record Tenant(UUID id,String code) { }
    /** Copies to the upload location; the original HR file is never moved/deleted. */
    private record FileUpload(Path path) implements MultipartFile {
        public String getName(){return "file";}public String getOriginalFilename(){return "roster.csv";}public String getContentType(){return "text/csv";}
        public boolean isEmpty(){return getSize()==0;}public long getSize(){try{return Files.size(path);}catch(IOException e){throw new UncheckedIOException(e);}}
        public byte[] getBytes(){throw new UnsupportedOperationException("Streaming only");}public InputStream getInputStream()throws IOException{return Files.newInputStream(path);}
        public void transferTo(File destination)throws IOException{Files.copy(path,destination.toPath());}public void transferTo(Path destination)throws IOException{Files.copy(path,destination);}
    }
}
