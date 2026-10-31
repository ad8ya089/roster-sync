package com.moveinsync.rostersync.domain.validation;
import com.moveinsync.rostersync.domain.model.*;
import com.moveinsync.rostersync.domain.ownership.FieldOwnershipPolicy;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class UnknownFieldWarningsTest {
 private final UUID tenant=UUID.randomUUID();
 private final RecordValidator validator=new RecordValidator(FieldOwnershipPolicy.defaults(),(id,code)->OfficeLookup.Status.ACTIVE);
 private Map<EmployeeField,String> fields(){return Map.of(EmployeeField.NAME,"Fixture",EmployeeField.EMAIL,"fixture@example.test",EmployeeField.OFFICE_CODE,"BLR-1",EmployeeField.STATUS,"ACTIVE");}
    @Test void unknownPropertyWarningsAreBoundedAndNeverReflectUnsafeNames() {
        var unknown=new java.util.HashSet<String>();
        for(int n=0;n<30;n++) unknown.add("field"+n);
        unknown.add("canary.person@example.test");unknown.add("A".repeat(41));
        var result=validator.validateEvent(tenant,"E1",1,"UPDATE",fields(),unknown);
        assertThat(result.warnings()).hasSizeLessThanOrEqualTo(10);
        assertThat(result.warnings()).allSatisfy(warning->assertThat(warning.field()).matches("[A-Za-z][A-Za-z0-9_]{0,39}|\\(unrecognised\\)"));
        var unsafe=validator.validateFileRow(tenant,"E1",1,fields(),Set.of("canary.person@example.test"));
        assertThat(unsafe.warnings()).containsExactly(new FieldError("(unrecognised)","UNKNOWN_FIELD_IGNORED"));
    }
}
