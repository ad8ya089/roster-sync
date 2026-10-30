package com.moveinsync.rostersync;

import com.moveinsync.rostersync.domain.validation.OfficeLookup;
import com.moveinsync.rostersync.domain.model.EmployeeField;
import com.moveinsync.rostersync.domain.ownership.FieldOwnershipPolicy;
import com.moveinsync.rostersync.domain.validation.RecordValidator;
import com.moveinsync.rostersync.infrastructure.persistence.TenantSettingsRepository;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.*;

class TenantIsolationIT extends AbstractSecurityIntegrationTest {
    @Autowired private OfficeLookup lookup;
    @Autowired private TenantSettingsRepository settings;

    @Test void globexCannotReadOrPatchAcmeOfficeAndListsOnlyItsOwnOffices() throws Exception {
        String globex = token("globex.admin");
        problem(request("GET", "/api/v1/offices/BLR-1", globex, null), 404, "NOT_FOUND");
        problem(request("PATCH", "/api/v1/offices/BLR-1", globex, "{\"active\":false}"), 404, "NOT_FOUND");
        var response = request("GET", "/api/v1/offices", globex, null);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("HYD-1").doesNotContain("BLR-1", "BLR-2", "BLR-3");
        assertThat(jdbc.queryForObject("SELECT active FROM office WHERE tenant_id=? AND code='BLR-1'", Boolean.class, JwtTestTokens.ACME)).isTrue();
        assertThat(settings.findByTenantId(JwtTestTokens.ACME)).isPresent();
    }

    @Test void sameOfficeCodeMayExistInBothTenantsButDuplicatesWithinTenantConflict() throws Exception {
        String acme = token("acme.admin"), globex = token("globex.admin");
        String body = "{\"code\":\"SHARED-1\",\"name\":\"Office\"}";
        assertThat(request("POST", "/api/v1/offices", acme, body).statusCode()).isEqualTo(201);
        assertThat(request("POST", "/api/v1/offices", globex, body).statusCode()).isEqualTo(201);
        problem(request("POST", "/api/v1/offices", acme, body), 409, "OFFICE_CODE_EXISTS");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM office WHERE code='SHARED-1'", Integer.class)).isEqualTo(2);
    }

    @Test void deactivationPreservesEmployeesAndImmediatelyChangesTheLookupPort() throws Exception {
        String acme = token("acme.admin");
        assertThat(lookup.lookup(JwtTestTokens.ACME, "BLR-1")).isEqualTo(OfficeLookup.Status.ACTIVE);
        var response = request("PATCH", "/api/v1/offices/BLR-1", acme, "{\"name\":\"Renamed\",\"active\":false}");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(response.body()).get("active").asBoolean()).isFalse();
        assertThat(lookup.lookup(JwtTestTokens.ACME, "BLR-1")).isEqualTo(OfficeLookup.Status.INACTIVE);
        var validator = new RecordValidator(FieldOwnershipPolicy.defaults(), lookup);
        var validation = validator.validateEvent(JwtTestTokens.ACME, "E2001", 1, "CREATE", Map.of(
                EmployeeField.NAME, "Employee", EmployeeField.EMAIL, "employee@example.test",
                EmployeeField.OFFICE_CODE, "BLR-1"), Set.of());
        assertThat(validation.errors()).anyMatch(error -> error.code().equals("INACTIVE_OFFICE"));
        assertThat(lookup.lookup(JwtTestTokens.ACME, "UNKNOWN")).isEqualTo(OfficeLookup.Status.UNKNOWN);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM employee WHERE tenant_id=? AND status='ACTIVE' AND office_id IS NOT NULL",
                Integer.class, JwtTestTokens.ACME)).isEqualTo(5);
        assertThat(request("GET", "/api/v1/offices?includeInactive=false", acme, null).body()).doesNotContain("BLR-1", "BLR-3");
        assertThat(request("PATCH", "/api/v1/offices/BLR-1", acme, "{\"active\":true}").statusCode()).isEqualTo(200);
        assertThat(lookup.lookup(JwtTestTokens.ACME, "BLR-1")).isEqualTo(OfficeLookup.Status.ACTIVE);
    }

    @Test void codeAndNameBoundariesAreValidated() throws Exception {
        String acme = token("acme.admin");
        for (int length : new int[]{1, 200}) {
            String body = mapper.writeValueAsString(Map.of("code", "A".repeat(length == 1 ? 1 : 32), "name", "N".repeat(length)));
            assertThat(request("POST", "/api/v1/offices", acme, body).statusCode()).isEqualTo(201);
        }
        for (String code : new String[]{"", "lowercase", "A_B", "A".repeat(33)}) {
            problem(request("POST", "/api/v1/offices", acme, mapper.writeValueAsString(Map.of("code", code, "name", "N"))), 400, "VALIDATION_FAILED");
        }
        for (String name : new String[]{"", " ", "N".repeat(201)}) {
            problem(request("POST", "/api/v1/offices", acme, mapper.writeValueAsString(Map.of("code", "NEW-1", "name", name))), 400, "VALIDATION_FAILED");
            problem(request("PATCH", "/api/v1/offices/BLR-1", acme, mapper.writeValueAsString(Map.of("name", name))), 400, "VALIDATION_FAILED");
        }
    }
}
