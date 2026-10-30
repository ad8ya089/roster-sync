package com.moveinsync.rostersync.config;
import com.moveinsync.rostersync.domain.model.EmployeeField;
import com.moveinsync.rostersync.domain.ownership.FieldOwnershipPolicy;
import com.moveinsync.rostersync.domain.hash.RecordHasher;
import com.moveinsync.rostersync.domain.decision.ChangeDecider;
import com.moveinsync.rostersync.domain.validation.*;
import java.util.stream.Collectors;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods = false)
public class DomainConfiguration {
    @Bean FieldOwnershipPolicy fieldOwnershipPolicy(RosterProperties properties) {
        return new FieldOwnershipPolicy(properties.ownership().transportOwned().stream().map(EmployeeField::valueOf).collect(Collectors.toSet()));
    }
    @Bean RecordHasher recordHasher(FieldOwnershipPolicy policy) { return new RecordHasher(policy); }
    @Bean ChangeDecider changeDecider(FieldOwnershipPolicy policy, RecordHasher hasher) { return new ChangeDecider(policy, hasher); }
    @Bean RecordValidator recordValidator(FieldOwnershipPolicy policy, OfficeLookup offices) { return new RecordValidator(policy, offices); }
}
