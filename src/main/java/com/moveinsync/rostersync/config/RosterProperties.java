package com.moveinsync.rostersync.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("roster")
public record RosterProperties(
        @Valid @NotNull @DefaultValue Ownership ownership,
        @Valid @NotNull @DefaultValue Reconciliation reconciliation,
        @Valid @NotNull @DefaultValue Outbox outbox,
        @Valid @NotNull @DefaultValue Retention retention,
        @Valid @NotNull @DefaultValue Security security,
        @Valid @NotNull @DefaultValue Cache cache,
        @Valid @NotNull @DefaultValue Metrics metrics) {

    public record Ownership(
            @DefaultValue({"PICKUP_POINT", "SPECIAL_ASSISTANCE"}) Set<@NotBlank String> transportOwned) {
        public Ownership {
            transportOwned = Set.copyOf(transportOwned);
        }
    }

    public record Reconciliation(
            @Min(1) @Max(32768) @DefaultValue("256") int buckets,
            @Valid @NotNull @DefaultValue Brake brake,
            @Min(1) @DefaultValue("40") int chunkSize,
            @NotNull @DefaultValue("/data") Path dataDir,
            Path inboxDir,
            @Min(1) @DefaultValue("1000") int insertBatchSize,
            @Min(1) @DefaultValue("10") int queueCapacity,
            @NotNull @DefaultValue("5m") Duration recoveryInterval,
            @NotBlank @DefaultValue("0 0 1 * * *") String inboxCron) {
        public Reconciliation {
            if (inboxDir != null && inboxDir.toString().isEmpty()) {
                inboxDir = null;
            }
        }

        @AssertTrue(message = "recovery interval must be positive")
        public boolean isRecoveryIntervalPositive() {
            return positive(recoveryInterval);
        }
    }

    public record Brake(
            @NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax("1")
            @DefaultValue("0.30") BigDecimal maxMissingRatio,
            @Min(1) Integer maxMissingCount) { }

    public record Outbox(
            @DefaultValue("true") boolean enabled,
            @NotNull @DefaultValue("500ms") Duration pollInterval,
            @Min(1) @DefaultValue("100") int batchSize,
            @Min(1) @DefaultValue("10") int maxAttempts,
            @NotNull @DefaultValue("2s") Duration sinkTimeout) {
        @AssertTrue(message = "outbox intervals must be positive")
        public boolean isTimingPositive() {
            return positive(pollInterval) && positive(sinkTimeout);
        }
    }

    public record Retention(
            @NotNull @DefaultValue("30d") Duration receipts,
            @NotNull @DefaultValue("7d") Duration outbox,
            @NotNull @DefaultValue("7d") Duration reconActions,
            @NotNull @DefaultValue("7d") Duration processedEvents,
            @NotNull @DefaultValue("7d") Duration uploads,
            @NotBlank @DefaultValue("0 0 3 * * *") String cron) {
        @AssertTrue(message = "retention durations must be positive")
        public boolean isDurationPositive() {
            return positive(receipts) && positive(outbox) && positive(reconActions)
                    && positive(processedEvents) && positive(uploads);
        }
    }

    public record Security(
            @NotBlank String jwtSecret,
            @NotNull @DefaultValue("1h") Duration tokenTtl) {
        @AssertTrue(message = "JWT secret must contain at least 32 UTF-8 bytes")
        public boolean isSecretStrongEnough() {
            return jwtSecret != null && jwtSecret.getBytes(StandardCharsets.UTF_8).length >= 32;
        }

        @AssertTrue(message = "token TTL must be positive")
        public boolean isTokenTtlPositive() {
            return positive(tokenTtl);
        }

        @Override
        public String toString() {
            return "Security[jwtSecret=<redacted>, tokenTtl=" + tokenTtl + "]";
        }
    }

    public record Cache(
            @Valid @NotNull @DefaultValue OfficeCache offices,
            @Valid @NotNull @DefaultValue EmployeeCache employeeView) { }

    public record OfficeCache(
            @NotNull @DefaultValue("10m") Duration ttl,
            @Min(1) @DefaultValue("10000") long maximumSize) {
        @AssertTrue(message = "cache TTL must be positive")
        public boolean isTtlPositive() { return positive(ttl); }
    }

    public record EmployeeCache(
            @NotNull @DefaultValue("30s") Duration ttl,
            @Min(1) @DefaultValue("50000") long maximumSize) {
        @AssertTrue(message = "cache TTL must be positive")
        public boolean isTtlPositive() { return positive(ttl); }
    }

    public record Metrics(@NotNull @DefaultValue("15s") Duration refreshInterval) {
        @AssertTrue(message = "metrics refresh interval must be positive")
        public boolean isRefreshIntervalPositive() { return positive(refreshInterval); }
    }

    private static boolean positive(Duration value) {
        return value != null && !value.isZero() && !value.isNegative();
    }
}
