package com.moveinsync.rostersync.infrastructure.persistence;
import org.postgresql.util.PSQLException;

public final class PostgresConstraints {
    private PostgresConstraints() { }
    public static boolean uniqueViolation(Throwable exception, String constraint) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof PSQLException postgres && "23505".equals(postgres.getSQLState()) && postgres.getServerErrorMessage() != null
                    && constraint.equals(postgres.getServerErrorMessage().getConstraint())) return true;
        }
        return false;
    }
}
