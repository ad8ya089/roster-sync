package com.moveinsync.rostersync.application.recon;
import com.moveinsync.rostersync.domain.model.HrSnapshot;
import java.io.*;
import java.util.UUID;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DemoRosterExportService {
    private final JdbcTemplate jdbc;private final RosterFileGenerator generator;
    public DemoRosterExportService(JdbcTemplate jdbc,RosterFileGenerator generator){this.jdbc=jdbc;this.generator=generator;}
    @Transactional(readOnly=true)
    public RosterFileGenerator.Expected write(UUID tenantId,OutputStream output,RosterFileGenerator.Spec spec) throws IOException {
        // A large cursor export may outlast the normal 30s statement budget. Keep the
        // allowance local to this streaming transaction, never on a pooled connection.
        jdbc.execute("SET LOCAL statement_timeout = '5min'");
        // PostgreSQL cursor streaming requires BOTH autocommit off (this transaction) and fetchSize.
        try(var session=generator.open(output,spec)) {
            jdbc.query(connection->{
                var query=connection.prepareStatement("""
                        SELECT e.external_id,e.hr_version,e.name,e.email,e.phone,e.manager_external_id,o.code,e.shift_code,e.status
                        FROM employee e LEFT JOIN office o ON o.id=e.office_id AND o.tenant_id=e.tenant_id
                        WHERE e.tenant_id=? ORDER BY e.external_id
                        """);query.setObject(1,tenantId);query.setFetchSize(1000);return query;
            },(RowCallbackHandler)row->{try{session.accept(row.getString(1),row.getLong(2),new HrSnapshot(row.getString(3),row.getString(4),row.getString(5),row.getString(6),row.getString(7),row.getString(8),row.getString(9)));}
                catch(IOException failure){throw new UncheckedIOException(failure);}});
            return session.finish();
        }catch(UncheckedIOException failure){throw failure.getCause();}
    }
}
