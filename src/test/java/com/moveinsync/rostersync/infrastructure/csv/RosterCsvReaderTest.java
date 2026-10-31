package com.moveinsync.rostersync.infrastructure.csv;
import com.moveinsync.rostersync.domain.hash.RecordHasher;
import com.moveinsync.rostersync.domain.model.HrSnapshot;
import com.moveinsync.rostersync.domain.ownership.FieldOwnershipPolicy;
import com.moveinsync.rostersync.domain.validation.OfficeLookup;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class RosterCsvReaderTest {
    @Test void delInAFileNameFieldIsRejectedWithoutLosingTheNextRow() throws Exception {
        var file=directory.resolve("del.csv");
        Files.writeString(file,HEADER+"E1,1,Fixture\u007fPerson,f@example.test,+919800000001,,BLR-1,S1,ACTIVE\nE2,1,Fixture,f@example.test,+919800000001,,BLR-1,S1,ACTIVE\n");
        var rows=new ArrayList<RosterCsvReader.Row>();
        var result=reader.read(file,UUID.randomUUID(),FieldOwnershipPolicy.defaults(),(tenant,code)->OfficeLookup.Status.ACTIVE,rows::add);
        assertThat(result.valid()).isEqualTo(1);
        assertThat(rows.getFirst().errors()).contains(new com.moveinsync.rostersync.domain.validation.FieldError("name","INVALID_FORMAT"));
        assertThat(rows.getLast().snapshot()).isNotNull();
    }
    @TempDir Path directory;
    private final RosterCsvReader reader=new RosterCsvReader();
    private static final String HEADER=String.join(",",RosterCsvReader.COLUMNS)+"\n";
    @Test void stripsBomAndOnlyRecognizesTheLastRecordAsATrailer() throws Exception {
        var file=directory.resolve("input.csv");Files.writeString(file,"\ufeff"+HEADER+"#TRAILER,row_count=10\nE1,1,Name,NAME@EXAMPLE.TEST,+919800000001,,BLR-1,S1,ACTIVE\n#TRAILER,row_count=2\n");
        assertThat(reader.header(file)).containsExactlyElementsOf(RosterCsvReader.COLUMNS);
        var rows=new ArrayList<RosterCsvReader.Row>();var result=reader.read(file,UUID.randomUUID(),FieldOwnershipPolicy.defaults(),(tenant,code)->OfficeLookup.Status.ACTIVE,rows::add);
        assertThat(result.total()).isEqualTo(2);assertThat(result.valid()).isEqualTo(1);assertThat(result.trailerCount()).isEqualTo(2);
        assertThat(rows.getFirst().errors()).isNotEmpty();assertThat(rows.getLast().snapshot().email()).isEqualTo("name@example.test");
    }
    @Test void duplicateHeaderAndUnclosedQuoteReturnSafeCodesOnly() throws Exception {
        var file=directory.resolve("bad.csv");Files.writeString(file,HEADER.strip()+",name\n");
        assertThatThrownBy(()->reader.header(file)).isInstanceOf(RosterCsvReader.InvalidHeader.class).hasMessage("INVALID_CSV_HEADER");
        Files.writeString(file,HEADER+"E1,1,\"CANARY_SECRET");
        assertThatThrownBy(()->reader.read(file,UUID.randomUUID(),FieldOwnershipPolicy.defaults(),(tenant,code)->OfficeLookup.Status.ACTIVE,row->{}))
                .isInstanceOf(RosterCsvReader.MalformedCsv.class).hasMessage("MALFORMED_CSV");
    }
    @Test void binarySpillPreservesNullsQuotesTabsAndNewlinesAndResolvesDuplicates() throws Exception {
        var hash=new RecordHasher(FieldOwnershipPolicy.defaults());var spill=directory.resolve(UUID.randomUUID().toString());
        var value=new HrSnapshot("Name\t\n\"", "a@example.test",null,null,"BLR-1","S1","ACTIVE");
        try(var writer=new BucketSpillWriter(spill,1,hash)) {
            writer.write(new RosterCsvReader.Row(1,"E1",1,value,List.of()));writer.write(new RosterCsvReader.Row(2,"E1",3,value,List.of()));
            writer.write(new RosterCsvReader.Row(3,"E2",2,value,List.of()));writer.write(new RosterCsvReader.Row(4,"E2",2,value.withStatus("INACTIVE"),List.of()));
        }
        var duplicates=new ArrayList<Long>();var result=new BucketSpillReader().read(BucketSpillWriter.file(spill,0),(number,id)->duplicates.add(number));
        assertThat(result.rows().get("E1").version()).isEqualTo(3);assertThat(result.rows().get("E1").snapshot()).isEqualTo(value);
        assertThat(result.presentButInvalid()).containsExactly("E2");assertThat(result.rows()).doesNotContainKey("E2");assertThat(duplicates).containsExactly(2L,4L);
    }
}
