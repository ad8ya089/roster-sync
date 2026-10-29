package com.moveinsync.rostersync.domain.model;

public record HrSnapshot(String name, String email, String phone, String managerId, String officeCode,
                         String shiftCode, String status, String pickupPoint, String specialAssistance) {
    public HrSnapshot {
        email = EmployeeField.EMAIL.canonicalValue(email);
    }

    public HrSnapshot(String name, String email, String phone, String managerId, String officeCode,
                      String shiftCode, String status) {
        this(name, email, phone, managerId, officeCode, shiftCode, status, null, null);
    }

    public String get(EmployeeField field) {
        return switch (field) {
            case NAME -> name;
            case EMAIL -> email;
            case PHONE -> phone;
            case MANAGER_ID -> managerId;
            case OFFICE_CODE -> officeCode;
            case SHIFT_CODE -> shiftCode;
            case STATUS -> status;
            case PICKUP_POINT -> pickupPoint;
            case SPECIAL_ASSISTANCE -> specialAssistance;
        };
    }

    public HrSnapshot withStatus(String newStatus) {
        return new HrSnapshot(name, email, phone, managerId, officeCode, shiftCode, newStatus, pickupPoint, specialAssistance);
    }

    public static HrSnapshot tombstone() {
        return new HrSnapshot(null, null, null, null, null, null, "INACTIVE");
    }

    @Override
    public String toString() {
        return "HrSnapshot[managerId=" + managerId + ", officeCode=" + officeCode
                + ", shiftCode=" + shiftCode + ", status=" + status + "]";
    }
}
