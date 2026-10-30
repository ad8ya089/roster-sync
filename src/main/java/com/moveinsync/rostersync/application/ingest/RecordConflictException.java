package com.moveinsync.rostersync.application.ingest;

/** Escapes the transaction proxy so a provisional receipt is rolled back before the audit transaction. */
public final class RecordConflictException extends RuntimeException {
    private final long currentVersion;
    public RecordConflictException(long currentVersion) { super("VERSION_CONFLICT"); this.currentVersion = currentVersion; }
    public long currentVersion() { return currentVersion; }
}
