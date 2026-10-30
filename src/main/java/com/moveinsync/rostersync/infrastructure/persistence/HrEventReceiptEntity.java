package com.moveinsync.rostersync.infrastructure.persistence;
import jakarta.persistence.*;
import java.time.Instant;

@Entity @Table(name = "hr_event_receipt") @Access(AccessType.FIELD)
public class HrEventReceiptEntity extends TenantScopedEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "external_id", nullable = false, length = 64) private String externalId;
    @Column(nullable = false) private long version;
    @Column(name = "event_id", length = 100) private String eventId;
    @Column(name = "event_type", nullable = false, length = 12) private String eventType;
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.CHAR)
    @Column(name = "payload_hash", nullable = false, length = 64, columnDefinition = "char(64)") private String payloadHash;
    @Column(nullable = false, length = 16) private String outcome;
    @Column(length = 200) private String warnings;
    @Column(name = "received_at", nullable = false) private Instant receivedAt;
    protected HrEventReceiptEntity() { }
    public long getVersion() { return version; }
    public String getEventType() { return eventType; }
    public String getOutcome() { return outcome; }
    public Instant getReceivedAt() { return receivedAt; }
}
