package com.moveinsync.rostersync.infrastructure.persistence;
import com.moveinsync.rostersync.domain.model.*;
import com.moveinsync.rostersync.domain.recon.DbRow;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class EmployeeBucketDao {
    private final JdbcClient jdbc;
    public EmployeeBucketDao(JdbcClient jdbc){this.jdbc=jdbc;}
    public List<DbRow> findBucket(UUID tenantId,int bucket) {
        // Include inactive rows/tombstones and join by tenant as well as FK id.
        return jdbc.sql("""
                SELECT e.external_id,e.hr_version,e.status,e.hr_updated_at,e.name,e.email,e.phone,e.manager_external_id,o.code,e.shift_code
                FROM employee e LEFT JOIN office o ON o.id=e.office_id AND o.tenant_id=e.tenant_id
                WHERE e.tenant_id=? AND e.recon_bucket=?
                """).params(tenantId,bucket).query((row,n)->new DbRow(row.getString(1),row.getLong(2),EmploymentStatus.valueOf(row.getString(3)),
                        row.getTimestamp(4).toInstant(),new HrSnapshot(row.getString(5),row.getString(6),row.getString(7),row.getString(8),row.getString(9),row.getString(10),row.getString(3)))).list();
    }
}
