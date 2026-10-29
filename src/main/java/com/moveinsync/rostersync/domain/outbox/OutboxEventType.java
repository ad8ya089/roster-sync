package com.moveinsync.rostersync.domain.outbox;

public enum OutboxEventType {
    CREATED("EmployeeCreated"), UPDATED("EmployeeUpdated"), DEACTIVATED("EmployeeDeactivated"),
    REACTIVATED("EmployeeReactivated"), TRANSPORT_PROFILE_UPDATED("TransportProfileUpdated"),
    RECONCILIATION_BRAKE_TRIPPED("ReconciliationBrakeTripped");

    private final String wireName;
    OutboxEventType(String wireName) { this.wireName = wireName; }
    public String wireName() { return wireName; }
}
