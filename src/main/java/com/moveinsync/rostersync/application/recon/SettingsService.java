package com.moveinsync.rostersync.application.recon;
import com.moveinsync.rostersync.config.RosterProperties;
import com.moveinsync.rostersync.domain.recon.BrakeSettings;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SettingsService {
    private final JdbcClient jdbc;private final RosterProperties properties;private final Clock clock;
    public SettingsService(JdbcClient jdbc,RosterProperties properties,Clock clock){this.jdbc=jdbc;this.properties=properties;this.clock=clock;}
    @Transactional(readOnly=true)
    public View get(UUID tenantId) {
        var defaults=properties.reconciliation().brake();
        return jdbc.sql("SELECT brake_max_missing_ratio,brake_max_missing_count FROM tenant_settings WHERE tenant_id=?").param(tenantId)
                .query((row,n)->new View(row.getBigDecimal(1)==null?defaults.maxMissingRatio():row.getBigDecimal(1),
                        row.getObject(2)==null?defaults.maxMissingCount():Integer.valueOf(row.getInt(2)),
                        row.getObject(1)==null && row.getObject(2)==null?"DEFAULT":"TENANT")).single();
    }
    @Transactional
    public View set(UUID tenantId,BigDecimal ratio,Integer count,String username) {
        jdbc.sql("UPDATE tenant_settings SET brake_max_missing_ratio=?,brake_max_missing_count=?,updated_at=?,updated_by=? WHERE tenant_id=?")
                .params(ratio,count,Timestamp.from(clock.instant()),username,tenantId).update();return get(tenantId);
    }
    public record View(BigDecimal maxMissingRatio,Integer maxMissingCount,String source) {
        public BrakeSettings brake(){return new BrakeSettings(maxMissingRatio,maxMissingCount==null?null:maxMissingCount.longValue());}
    }
}
