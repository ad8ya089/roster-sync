package com.moveinsync.rostersync.application.recon;
import com.moveinsync.rostersync.api.error.*;
import com.moveinsync.rostersync.config.RosterProperties;
import com.moveinsync.rostersync.domain.validation.FieldError;
import com.moveinsync.rostersync.infrastructure.csv.RosterCsvReader;
import com.moveinsync.rostersync.infrastructure.persistence.*;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class ReconciliationUploadService {
    private final ReconRunDao runs;private final RosterCsvReader csv;private final Path root;private final Clock clock;
    private final ReconWorkQueue queue;private final ObjectProvider<ReconciliationRunner> runner;
    public ReconciliationUploadService(ReconRunDao runs,RosterCsvReader csv,RosterProperties properties,Clock clock,ReconWorkQueue queue,ObjectProvider<ReconciliationRunner> runner) {
        this.runs=runs;this.csv=csv;root=properties.reconciliation().dataDir().resolve("recon");this.clock=clock;this.queue=queue;this.runner=runner;
    }
    public Accepted upload(UUID tenantId,String username,MultipartFile file,Instant asOf,Integer expected,boolean dryRun) throws IOException {
        if(file.isEmpty()) throw invalid("file");if(asOf.isAfter(clock.instant().plusSeconds(300))) throw invalid("asOf");
        UUID id=UUID.randomUUID();Files.createDirectories(root);Path destination=root.resolve(id+".csv").toAbsolutePath();boolean accepted=false;
        try {
            file.transferTo(destination);
            try{csv.header(destination);}catch(RosterCsvReader.InvalidHeader invalid){throw new RequestValidationException(ErrorCode.INVALID_CSV_HEADER,List.of(new FieldError("file","INVALID_CSV_HEADER")));}
            try{runs.create(tenantId,id,destination,file.getSize(),asOf,expected,dryRun,username);}
            catch(DataIntegrityViolationException conflict){if(PostgresConstraints.uniqueViolation(conflict,"ux_recon_one_active")) throw new ConflictException(ErrorCode.RECON_RUN_ALREADY_ACTIVE);throw conflict;}
            accepted=true;
            try{queue.submit(id,()->runner.getObject().run(tenantId,id));}
            catch(RuntimeException unavailable){runs.fail(tenantId,id,"UPLOADED","QUEUE_UNAVAILABLE");throw unavailable;}
            return new Accepted(id,"UPLOADED");
        } finally {if(!accepted) Files.deleteIfExists(destination);}
    }
    private static RequestValidationException invalid(String field){return new RequestValidationException(ErrorCode.VALIDATION_FAILED,List.of(new FieldError(field,"INVALID_FORMAT")));}
    public record Accepted(UUID runId,String status) { }
}
