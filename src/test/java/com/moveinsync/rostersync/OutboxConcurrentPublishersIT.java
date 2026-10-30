package com.moveinsync.rostersync;

import com.moveinsync.rostersync.application.outbox.OutboxPublisher;
import com.moveinsync.rostersync.config.RosterProperties;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import static org.assertj.core.api.Assertions.*;

@Timeout(30)
class OutboxConcurrentPublishersIT extends AbstractOutboxIntegrationTest {
    @Autowired RosterProperties properties;
    @Test void twoPollersDeliverFiveHundredRowsExactlyOnce() throws Exception {
        for(int index=0;index<500;index++) pending("C"+index,"EmployeeUpdated");
        var first=new OutboxPublisher(processor,properties);var second=new OutboxPublisher(processor,properties);
        var gate=new CountDownLatch(1);
        try(var workers=Executors.newFixedThreadPool(2)) {
            var one=workers.submit(()->{gate.await();for(int tick=0;tick<5;tick++) first.tick();return null;});
            var two=workers.submit(()->{gate.await();for(int tick=0;tick<5;tick++) second.tick();return null;});
            gate.countDown();one.get(25,TimeUnit.SECONDS);two.get(25,TimeUnit.SECONDS);
        }
        assertThat(count("outbox_event")).isEqualTo(500);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE status='PUBLISHED'",Long.class)).isEqualTo(500);
        assertThat(sink.deliveries()).hasSize(500).allSatisfy((id,count)->assertThat(count).isEqualTo(1));
    }
}
