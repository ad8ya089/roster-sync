package com.moveinsync.rostersync.application.ingest;
import com.moveinsync.rostersync.domain.model.*;
import com.moveinsync.rostersync.domain.ownership.FieldOwnershipPolicy;
import com.moveinsync.rostersync.domain.validation.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class HrEventInputParserTest {
    @Test void eventIdsRejectControlsAndDatabaseColumnOverflow() {
        for(int control=0;control<32;control++) {
            var parsed=parser.parse(UUID.randomUUID(),Map.of("type","DEACTIVATE","employeeId","E1","version",1,"eventId","HR"+(char)control));
            assertThat(parsed.validation().errors()).contains(new FieldError("eventId","INVALID_FORMAT"));
        }
        var parsed=parser.parse(UUID.randomUUID(),Map.of("type","DEACTIVATE","employeeId","E1","version",1,"eventId","x".repeat(101)));
        assertThat(parsed.validation().errors()).contains(new FieldError("eventId","TOO_LONG"));
    }
    private final FieldOwnershipPolicy ownership=FieldOwnershipPolicy.defaults();
    private final HrEventInputParser parser=new HrEventInputParser(ownership,new RecordValidator(ownership,(tenant,code)->OfficeLookup.Status.ACTIVE));
    @Test void filtersNonOwnedValuesBeforeAttemptingToValidateTheirJsonTypes() {
        var parsed=parser.parse(UUID.randomUUID(),Map.of("type","CREATE","employeeId","E1","version",1,
                "name","Name","email","fixture@example.test","officeCode","BLR-1",
                "pickupPoint",List.of("ignored"),"specialAssistance",Map.of("ignored",17)));
        assertThat(parsed.validation().valid()).isTrue();
        assertThat(parsed.validation().warnings()).hasSize(2).allMatch(warning->warning.code().equals("FIELD_NOT_OWNED_BY_SOURCE"));
    }
    @Test void normalizesEmailButRejectsUnnormalisedPhoneAndCollectsTypedErrors() {
        var fields=new LinkedHashMap<String,Object>(Map.of("type","CREATE","employeeId","E1","version",1,"name",17,"email"," A@EXAMPLE.TEST ","officeCode","BLR-1","phone","+91 9800000001"));
        var parsed=parser.parse(UUID.randomUUID(),fields);
        assertThat(parsed.validation().errors()).anyMatch(error->error.field().equals("phone") && error.code().equals("INVALID_FORMAT"));
        assertThat(parsed.validation().errors()).anyMatch(error->error.field().equals("name") && error.code().equals("INVALID_FORMAT"));
        fields.put("name"," Name "); fields.put("phone","+919800000001");
        parsed=parser.parse(UUID.randomUUID(),fields);
        assertThat(parsed.validation().valid()).isTrue();
        var snapshot=parsed.command().resolveSnapshot(Optional.empty());
        assertThat(snapshot.email()).isEqualTo("a@example.test"); assertThat(snapshot.name()).isEqualTo("Name"); assertThat(snapshot.status()).isEqualTo("ACTIVE");
    }
    @Test void ignoresDeactivateFieldsAndWarnsWithoutParsingThem() {
        var parsed=parser.parse(UUID.randomUUID(),Map.of("type","DEACTIVATE","employeeId","E1","version",8,"name",List.of(17),"phone",Map.of("private","value"),"unknown",7));
        assertThat(parsed.validation().valid()).isTrue();
        assertThat(parsed.validation().warnings()).hasSize(3).allMatch(warning->warning.code().equals("UNKNOWN_FIELD_IGNORED"));
    }
    @Test void invalidIdentifiersAndVersionsNeverEscapeAsRawInputValues() {
        var parsed=parser.parse(UUID.randomUUID(),Map.of("type","DEACTIVATE","employeeId","canary.person@pii.test","version",new java.math.BigInteger("9223372036854775808")));
        assertThat(parsed.employeeId()).isNull(); assertThat(parsed.validation().valid()).isFalse();
        assertThat(parsed.toString()).doesNotContain("canary.person@pii.test");
    }
}
