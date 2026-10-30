package com.moveinsync.rostersync.infrastructure.persistence;

import com.moveinsync.rostersync.domain.model.*;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity @Table(name = "employee") @Access(AccessType.FIELD)
public class EmployeeEntity extends AuditedTenantEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "external_id", nullable = false, length = 64) private String externalId;
    @Column(length = 200) private String name;
    @Column(length = 254) private String email;
    @Column(length = 20) private String phone;
    @Column(name = "manager_external_id", length = 64) private String managerId;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "office_id") private OfficeEntity office;
    @Column(name = "shift_code", length = 20) private String shiftCode;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 10) private EmploymentStatus status;
    @Column(name = "hr_version", nullable = false) private long hrVersion;
    @Column(name = "hr_updated_at", nullable = false) private Instant hrUpdatedAt;
    @Column(name = "pickup_point", length = 300) private String pickupPoint;
    @Column(name = "special_assistance", length = 300) private String specialAssistance;
    @Column(name = "transport_version", nullable = false) private int transportVersion;
    @Column(name = "transport_updated_at") private Instant transportUpdatedAt;
    @Column(name = "recon_bucket", nullable = false) private short reconBucket;
    @Column(name = "deactivated_at") private Instant deactivatedAt;
    @Column(name = "deactivation_source", length = 10) private String deactivationSource;

    protected EmployeeEntity() { }
    public EmployeeEntity(UUID tenantId, String externalId, int bucket, HrSnapshot snapshot,
                          long version, Instant at, OfficeEntity office) {
        setTenantId(tenantId);
        this.externalId = externalId;
        reconBucket = (short) bucket;
        setCreatedAt(at);
        applyHrSnapshot(snapshot, version, at, office);
    }

    public void applyHrSnapshot(HrSnapshot snapshot, long version, Instant at) {
        applyHrSnapshot(snapshot, version, at, office);
    }
    public void applyHrSnapshot(HrSnapshot snapshot, long version, Instant at, OfficeEntity resolvedOffice) {
        if (!Objects.equals(snapshot.officeCode(), resolvedOffice == null ? null : resolvedOffice.getCode())) {
            throw new IllegalArgumentException("Office must be resolved within the authenticated tenant");
        }
        name = snapshot.name(); email = snapshot.email(); phone = snapshot.phone();
        managerId = snapshot.managerId(); office = resolvedOffice; shiftCode = snapshot.shiftCode();
        status = EmploymentStatus.valueOf(snapshot.status());
        advanceHrVersion(version, at);
        if (status == EmploymentStatus.INACTIVE) {
            if (deactivatedAt == null) deactivatedAt = at;
            deactivationSource = "EVENT";
        } else { deactivatedAt = null; deactivationSource = null; }
    }
    public void advanceHrVersion(long version, Instant at) {
        if (version < hrVersion) throw new IllegalArgumentException("HR version cannot decrease");
        hrVersion = version; hrUpdatedAt = at; setUpdatedAt(at);
    }
    public void applyTransportFields(TransportFields fields, Instant at) {
        applyTransportFields(fields, at, office);
    }
    public void applyTransportFields(TransportFields fields, Instant at, OfficeEntity resolvedOffice) {
        for (var entry : fields.values().entrySet()) {
            switch (entry.getKey()) {
                case NAME -> name = entry.getValue();
                case EMAIL -> email = entry.getValue();
                case PHONE -> phone = entry.getValue();
                case MANAGER_ID -> managerId = entry.getValue();
                case OFFICE_CODE -> office = resolvedOffice;
                case SHIFT_CODE -> shiftCode = entry.getValue();
                case STATUS -> status = EmploymentStatus.valueOf(entry.getValue());
                case PICKUP_POINT -> pickupPoint = entry.getValue();
                case SPECIAL_ASSISTANCE -> specialAssistance = entry.getValue();
            }
        }
        transportVersion++; transportUpdatedAt = at; setUpdatedAt(at);
    }
    public void markDeactivatedByRecon(Instant at) {
        status = EmploymentStatus.INACTIVE; deactivatedAt = at; deactivationSource = "RECON"; setUpdatedAt(at);
    }
    public HrSnapshot snapshot() {
        return new HrSnapshot(name, email, phone, managerId, office == null ? null : office.getCode(), shiftCode,
                status.name(), pickupPoint, specialAssistance);
    }
    public CurrentEmployeeState currentState() { return new CurrentEmployeeState(true, hrVersion, status, snapshot(), hrUpdatedAt); }
    public String getExternalId() { return externalId; }
    public long getHrVersion() { return hrVersion; }
    public Instant getHrUpdatedAt() { return hrUpdatedAt; }
    public int getTransportVersion() { return transportVersion; }
    public Instant getDeactivatedAt() { return deactivatedAt; }
}
