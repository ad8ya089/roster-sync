package com.moveinsync.rostersync.application.employee;
import com.moveinsync.rostersync.api.error.*;
import com.moveinsync.rostersync.application.outbox.OutboxWriter;
import com.moveinsync.rostersync.domain.model.*;
import com.moveinsync.rostersync.domain.outbox.OutboxEventType;
import com.moveinsync.rostersync.domain.ownership.FieldOwnershipPolicy;
import com.moveinsync.rostersync.domain.validation.*;
import com.moveinsync.rostersync.infrastructure.persistence.*;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransportProfileService {
    private final EmployeeRepository employees;
    private final OfficeRepository offices;
    private final FieldOwnershipPolicy policy;
    private final RecordValidator validator;
    private final OutboxWriter outbox;
    private final Clock clock;
    public TransportProfileService(EmployeeRepository employees, OfficeRepository offices, FieldOwnershipPolicy policy,
                                   RecordValidator validator, OutboxWriter outbox, Clock clock) {
        this.employees=employees; this.offices=offices; this.policy=policy; this.validator=validator; this.outbox=outbox; this.clock=clock;
    }
    @Transactional
    public EmployeeView patch(UUID tenantId, String employeeId, Map<String,Object> body) {
        var fields = new EnumMap<EmployeeField,String>(EmployeeField.class);
        var errors = new ArrayList<FieldError>();
        for (var entry : body.entrySet()) {
            var known = Arrays.stream(EmployeeField.values()).filter(field -> field.jsonName().equals(entry.getKey())).findFirst();
            if (known.isEmpty()) errors.add(new FieldError("unknownField", "UNKNOWN_FIELD"));
            else if (policy.ownerOf(known.get()) != FieldOwner.TRANSPORT) errors.add(new FieldError(known.get().jsonName(), "FIELD_NOT_OWNED_BY_SOURCE"));
            else if (entry.getValue() != null && !(entry.getValue() instanceof String)) errors.add(new FieldError(known.get().jsonName(), "INVALID_FORMAT"));
            else fields.put(known.get(), known.get() == EmployeeField.EMAIL ? EmployeeField.EMAIL.canonicalValue((String)entry.getValue()) : (String)entry.getValue());
        }
        errors.addAll(validator.validateTransportFields(tenantId,employeeId,fields).errors());
        if (!errors.isEmpty()) invalid(errors);
        var employee = employees.findForUpdate(tenantId,employeeId).orElseThrow(NotFoundException::new);
        var before=employee.snapshot();
        String nextStatus=fields.containsKey(EmployeeField.STATUS) ? fields.get(EmployeeField.STATUS) : before.status();
        if (nextStatus == null) errors.add(new FieldError("status", "MISSING_REQUIRED_FIELD"));
        if ("ACTIVE".equals(nextStatus)) for (var required : List.of(EmployeeField.NAME,EmployeeField.EMAIL,EmployeeField.OFFICE_CODE)) {
            String value=fields.containsKey(required) ? fields.get(required) : before.get(required);
            if (value == null || value.isBlank()) errors.add(new FieldError(required.jsonName(),"MISSING_REQUIRED_FIELD"));
        }
        if (!errors.isEmpty()) invalid(errors);
        var changed=Arrays.stream(EmployeeField.values()).filter(fields::containsKey)
                .filter(field -> !Objects.equals(before.get(field),fields.get(field))).toList();
        if (changed.isEmpty()) return EmployeeView.from(employee);
        OfficeEntity office=null;
        if (fields.containsKey(EmployeeField.OFFICE_CODE) && fields.get(EmployeeField.OFFICE_CODE) != null) {
            office=offices.findForShare(tenantId,fields.get(EmployeeField.OFFICE_CODE)).orElseThrow(NotFoundException::new);
            offices.refreshForShare(tenantId,office);
            if (!office.isActive()) invalid(List.of(new FieldError("officeCode","INACTIVE_OFFICE")));
        }
        var now=clock.instant();
        if (fields.containsKey(EmployeeField.OFFICE_CODE)) employee.applyTransportFields(new TransportFields(fields),now,office);
        else employee.applyTransportFields(new TransportFields(fields),now);
        employees.flush(tenantId);
        outbox.write(tenantId,employeeId,employee.getHrVersion(),OutboxEventType.TRANSPORT_PROFILE_UPDATED,"TRANSPORT_ADMIN",changed,employee.snapshot(),now);
        return EmployeeView.from(employee);
    }
    private static void invalid(List<FieldError> errors) {
        var ordered=new ValidationResult(errors,List.of()).errors();
        ErrorCode code=ordered.stream().anyMatch(error -> error.code().equals("FIELD_NOT_OWNED_BY_SOURCE")) ? ErrorCode.FIELD_NOT_OWNED_BY_SOURCE
                : ordered.stream().anyMatch(error -> error.code().equals("UNKNOWN_FIELD")) ? ErrorCode.UNKNOWN_FIELD : ErrorCode.VALIDATION_FAILED;
        throw new RequestValidationException(code,ordered);
    }
}
