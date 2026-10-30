package com.moveinsync.rostersync.infrastructure.metrics;
import io.micrometer.core.instrument.*;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.*;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class GaugeRefresher {
    private static final Logger LOG=LoggerFactory.getLogger(GaugeRefresher.class);
    private final AtomicLong pending=new AtomicLong(),dead=new AtomicLong(),age=new AtomicLong(),awaiting=new AtomicLong();
    private final JdbcClient jdbc;private final Clock clock;
    public GaugeRefresher(JdbcClient jdbc,Clock clock,MeterRegistry registry){this.jdbc=jdbc;this.clock=clock;
        Gauge.builder("outbox.pending",pending,AtomicLong::get).register(registry);Gauge.builder("outbox.dead",dead,AtomicLong::get).register(registry);
        Gauge.builder("outbox.oldest.pending.age.seconds",age,AtomicLong::get).register(registry);Gauge.builder("recon.runs.awaiting.approval",awaiting,AtomicLong::get).register(registry);
    }
    @Scheduled(fixedDelayString="${roster.metrics.refresh-interval}",initialDelayString="${roster.metrics.refresh-interval}")
    public void refresh(){
        try {
            var snapshot=jdbc.sql("SELECT count(*) FILTER(WHERE status='PENDING'),count(*) FILTER(WHERE status='DEAD'),min(created_at) FILTER(WHERE status='PENDING') FROM outbox_event")
                    .query((row,n)->new Snapshot(row.getLong(1),row.getLong(2),row.getTimestamp(3)==null?0:Math.max(0,Duration.between(row.getTimestamp(3).toInstant(),clock.instant()).getSeconds()))).single();
            long approvals=jdbc.sql("SELECT count(*) FROM recon_run WHERE status='AWAITING_APPROVAL'").query(Long.class).single();
            pending.set(snapshot.pending());dead.set(snapshot.dead());age.set(snapshot.age());awaiting.set(approvals);
        }catch(DataAccessException unavailable){LOG.warn("metrics.gauge.refresh_failed exceptionClass={}",unavailable.getClass().getSimpleName());}
    }
    private record Snapshot(long pending,long dead,long age) { }
}
