package com.moveinsync.rostersync.infrastructure.persistence;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "record_rejection") @Access(AccessType.FIELD)
public class RecordRejectionEntity extends TenantScopedEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, length = 10) private String source;
    @Column(name = "run_id") private UUID runId;
    @Column(name = "row_number") private Integer rowNumber;
    @Column(name = "external_id", length = 64) private String externalId;
    private Long version;
    @Column(name = "error_codes", nullable = false, length = 500) private String errorCodes;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    protected RecordRejectionEntity() { }
}
