package com.moveinsync.rostersync;
import java.util.*;
import org.junit.jupiter.api.Test;
import com.moveinsync.rostersync.domain.model.Role;
import static org.assertj.core.api.Assertions.*;
class UnknownFieldWarningsIT extends AbstractIngestionIntegrationTest {
    @Test void unknownWarningsHaveABoundedListAndTotalCountWithoutArbitraryNames() throws Exception {
        var input=event("WARN1",1,"CREATE","BLR-1","S1");
        for(int n=0;n<30;n++)input.put("field"+n,"not reflected");
        input.put("canary.person@example.test",true);input.put("A".repeat(41),true);
        var response=request("POST","/api/v1/hr/events",jwt(Role.HR_SYSTEM),mapper.writeValueAsString(Map.of("events",List.of(input))));
        assertThat(response.statusCode()).isEqualTo(200);
        var record=mapper.readTree(response.body()).get("results").get(0);
        assertThat(record.get("warnings").size()).isLessThanOrEqualTo(10);
        assertThat(record.get("warningCount").asInt()).isEqualTo(32);
        assertThat(response.body()).doesNotContain("canary.person@example.test","A".repeat(41),"not reflected");
    }
}
