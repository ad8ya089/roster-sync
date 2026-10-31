package com.moveinsync.rostersync;

import com.moveinsync.rostersync.application.ops.RetentionJob;
import com.moveinsync.rostersync.application.recon.ReconciliationRecovery;
import com.moveinsync.rostersync.config.RosterProperties;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import static org.assertj.core.api.Assertions.*;

@TestPropertySource(properties="roster.reconciliation.data-dir=target/hardening-retention-it")
class ReconciliationRetentionIT extends AbstractOutboxIntegrationTest {
    @Autowired RetentionJob retention;
    @Autowired ReconciliationRecovery recovery;
    @Autowired RosterProperties properties;
    @Autowired com.moveinsync.rostersync.application.ops.RetentionBatch batches;
    private Path root(){return properties.reconciliation().dataDir().toAbsolutePath().resolve("recon");}

    @Test void eachActionDeletionStatementIsBoundedToFiveThousand() throws Exception {
        UUID id=run("COMPLETED");
        jdbc.update("INSERT INTO recon_action(tenant_id,run_id,external_id,action) SELECT ?,?,'BOUND'||n,'DEACTIVATE' FROM generate_series(1,5001) n",JwtTestTokens.ACME,id);
        clock.advance(Duration.ofDays(8));var cutoff=clock.instant().minus(properties.retention().reconActions());
        assertThat(batches.delete(com.moveinsync.rostersync.application.ops.RetentionBatch.Data.RECON_ACTIONS,cutoff)).isEqualTo(5000);
        assertThat(batches.delete(com.moveinsync.rostersync.application.ops.RetentionBatch.Data.RECON_ACTIONS,cutoff)).isEqualTo(1);
    }
    @Test void aDatabasePathOutsideTheGeneratedRootIsNeverDeleted(@org.junit.jupiter.api.io.TempDir Path outside) throws Exception {
        UUID id=run("COMPLETED");Path file=outside.resolve("keep.csv");Files.writeString(file,"synthetic");
        jdbc.update("UPDATE recon_run SET file_path=? WHERE id=?",file.toString(),id);
        clock.advance(Duration.ofDays(8));retention.run();assertThat(file).exists();
        assertThat(jdbc.queryForObject("SELECT file_path FROM recon_run WHERE id=?",String.class,id)).isEqualTo(file.toString());
    }

    @Test void deletesExpiredTerminalActionsAcrossBatchBoundaryAndUploadsButKeepsRunAudit() throws Exception {
        for(String status:new String[]{"COMPLETED","REJECTED","FAILED","PLANNED"}) {
            UUID id=run(status);Path file=root().resolve(id+".csv");
            jdbc.update("INSERT INTO recon_action(tenant_id,run_id,external_id,action) SELECT ?,?,'RET'||n,'DEACTIVATE' FROM generate_series(1,5001) n",JwtTestTokens.ACME,id);
            clock.advance(Duration.ofDays(8));retention.run();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM recon_action WHERE run_id=?",Long.class,id)).isZero();
            assertThat(file).doesNotExist();
            assertThat(jdbc.queryForObject("SELECT file_path FROM recon_run WHERE id=?",String.class,id)).isEqualTo("deleted");
            assertThat(jdbc.queryForObject("SELECT status FROM recon_run WHERE id=?",String.class,id)).isEqualTo(status);
        }
    }
    @ParameterizedTest @ValueSource(strings={"UPLOADED","DIFFING","APPLYING","AWAITING_APPROVAL"})
    void neverDeletesFilesOrActionsOfNonTerminalRuns(String status) throws Exception {
        UUID id=run(status);Path file=root().resolve(id+".csv");Path spill=spill(id);
        jdbc.update("INSERT INTO recon_action(tenant_id,run_id,external_id,action) VALUES (?,?,'KEEP','DEACTIVATE')",JwtTestTokens.ACME,id);
        clock.advance(Duration.ofDays(8));retention.run();
        assertThat(file).exists();assertThat(spill).exists();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM recon_action WHERE run_id=?",Long.class,id)).isEqualTo(1);
    }
    @Test void orphanUploadsAndAbandonedSpillsAreCleanedAtStartupAndNightly() throws Exception {
        for(boolean startup:new boolean[]{true,false}) {
            UUID unknown=UUID.randomUUID();Files.createDirectories(root());Path orphan=root().resolve(unknown+".csv");Files.writeString(orphan,"synthetic");
            // File age must use the same clock as cleanup, not the CI machine's wall clock.
            Files.setLastModifiedTime(orphan,FileTime.from(clock.instant()));
            Path orphanSpill=spill(unknown);UUID finished=run("COMPLETED");Path oldSpill=spill(finished);
            Path unrelated=root().resolve("do-not-delete.txt");Files.writeString(unrelated,"synthetic");
            Path fresh=root().resolve(UUID.randomUUID()+".csv");Files.writeString(fresh,"synthetic");
            clock.advance(Duration.ofDays(2));Files.setLastModifiedTime(fresh,FileTime.from(clock.instant()));
            if(startup) recovery.startup();else retention.run();
            assertThat(orphan).doesNotExist();assertThat(orphanSpill).doesNotExist();assertThat(oldSpill).doesNotExist();
            assertThat(root().resolve(finished+".csv")).exists();assertThat(fresh).exists();assertThat(unrelated).exists();
        }
    }
    @Test void retentionIsStrictlyOlderThanSevenDaysAndConfigurable() throws Exception {
        UUID id=run("PLANNED");Path file=root().resolve(id+".csv");
        clock.advance(Duration.ofDays(7));retention.run();assertThat(file).exists();
        clock.advance(Duration.ofSeconds(1));retention.run();assertThat(file).doesNotExist();
    }
    private UUID run(String status) throws Exception {
        UUID id=UUID.randomUUID();Files.createDirectories(root());Path file=root().resolve(id+".csv");Files.writeString(file,"synthetic");
        var now=Timestamp.from(clock.instant());
        jdbc.update("INSERT INTO recon_run(id,tenant_id,status,dry_run,file_path,as_of,created_by,created_at,finished_at) VALUES (?,?,?,false,?,?,'fixture',?,?)",id,JwtTestTokens.ACME,status,file.toString(),now,now,now);
        Files.setLastModifiedTime(file,FileTime.from(clock.instant()));return id;
    }
    private Path spill(UUID id) throws Exception {
        Path directory=root().resolve(id.toString());Files.createDirectories(directory);Files.writeString(directory.resolve("bucket-0.bin"),"synthetic");
        Files.setLastModifiedTime(directory,FileTime.from(clock.instant()));return directory;
    }
}
