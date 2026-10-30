package com.moveinsync.rostersync.application.employee;
import com.moveinsync.rostersync.infrastructure.persistence.EmployeeEntity;
import java.time.Instant;

public record EmployeeView(String employeeId, String status, long hrVersion, String name, String email, String phone,
                           String managerId, String officeCode, String shiftCode, TransportView transport, Instant hrUpdatedAt, Instant deactivatedAt) {
    public static EmployeeView from(EmployeeEntity employee) {
        var hr=employee.snapshot();
        return new EmployeeView(employee.getExternalId(), hr.status(), employee.getHrVersion(), hr.name(), hr.email(), hr.phone(),
                hr.managerId(), hr.officeCode(), hr.shiftCode(), new TransportView(hr.pickupPoint(), hr.specialAssistance(), employee.getTransportVersion()),
                employee.getHrUpdatedAt(), employee.getDeactivatedAt());
    }
    @Override public String toString() { return "EmployeeView[employeeId="+employeeId+", status="+status+", hrVersion="+hrVersion+"]"; }
    public record TransportView(String pickupPoint, String specialAssistance, int version) {
        @Override public String toString() { return "TransportView[version="+version+"]"; }
    }
}
