package com.moveinsync.rostersync.infrastructure.persistence;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** Queue metadata only. JSONB is written/read by JDBC, never serialized through Hibernate. */
@Entity @Table(name = "outbox_event") @Access(AccessType.FIELD)
public class OutboxEventEntity extends TenantScopedEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "event_id", nullable = false) private UUID eventId;
    @Column(name = "aggregate_type", nullable = false, length = 30) private String aggregateType;
    @Column(name = "aggregate_id", nullable = false, length = 64) private String aggregateId;
    @Column(name = "aggregate_version") private Long aggregateVersion;
    @Column(name = "event_type", nullable = false, length = 40) private String eventType;
    @Column(nullable = false, length = 10) private String status;
    @Column(nullable = false) private int attempts;
    @Column(name = "next_attempt_at", nullable = false) private Instant nextAttemptAt;
    @Column(name = "last_error", length = 200) private String lastError;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "published_at") private Instant publishedAt;
    protected OutboxEventEntity() { }
}
