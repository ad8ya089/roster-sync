package com.moveinsync.rostersync.infrastructure.csv;
import com.moveinsync.rostersync.domain.ownership.FieldOwnershipPolicy;
import com.moveinsync.rostersync.domain.validation.OfficeLookup;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
class UnknownCsvWarningsTest {
 @TempDir Path directory;
 private final RosterCsvReader reader=new RosterCsvReader();
 private static final String HEADER=String.join(",",RosterCsvReader.COLUMNS)+"\n";
    @Test void unknownCsvColumnWarningsAreSafeAndBoundedWithATotalCount() throws Exception {
        var extra=new ArrayList<String>();for(int n=0;n<30;n++)extra.add("field"+n);extra.add("canary.person@example.test");
        var file=directory.resolve("extra.csv");Files.writeString(file,HEADER.strip()+","+String.join(",",extra)+"\nE1,1,Name,n@example.test,+919800000001,,BLR-1,S1,ACTIVE"+",ignored".repeat(extra.size())+"\n");
        var result=reader.read(file,UUID.randomUUID(),FieldOwnershipPolicy.defaults(),(tenant,code)->OfficeLookup.Status.ACTIVE,row->{});
        var json=new tools.jackson.databind.json.JsonMapper().valueToTree(result);
        assertThat(json.has("warningCount")).isTrue();
        assertThat(json.get("warningCount").asInt()).isEqualTo(31);
        assertThat(json.get("warnings").size()).isLessThanOrEqualTo(10);
        assertThat(json.toString()).doesNotContain("canary.person@example.test");
    }
}
