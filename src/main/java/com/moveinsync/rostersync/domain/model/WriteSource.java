package com.moveinsync.rostersync.domain.model;

public enum WriteSource {
    HR_EVENT(FieldOwner.HR), HR_SNAPSHOT(FieldOwner.HR), TRANSPORT_ADMIN(FieldOwner.TRANSPORT);

    private final FieldOwner owner;
    WriteSource(FieldOwner owner) { this.owner = owner; }
    public FieldOwner owner() { return owner; }
}
