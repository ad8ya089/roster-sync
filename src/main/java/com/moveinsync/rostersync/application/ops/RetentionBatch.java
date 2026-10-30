package com.moveinsync.rostersync.application.ops;

import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class RetentionBatch {
    // SQL identifiers come only from this closed enum, never a request.
    public enum Data {
        RECEIPTS("hr_event_receipt","received_at","id",""),
        OUTBOX("outbox_event","published_at","id"," AND status='PUBLISHED'"),
        PROCESSED("processed_event","processed_at","(sink,event_id)","");
        private final String table,time,key,condition;
        Data(String table,String time,String key,String condition) {this.table=table;this.time=time;this.key=key;this.condition=condition;}
    }
    private final JdbcClient jdbc;
    public RetentionBatch(JdbcClient jdbc) {this.jdbc=jdbc;}
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public int delete(Data data,Instant cutoff) {
        // Global maintenance, bounded to 5,000 rows and committed before the next batch.
        return jdbc.sql("DELETE FROM "+data.table+" WHERE "+data.key+" IN (SELECT "+data.key+" FROM "+data.table+
                " WHERE "+data.time+"<?"+data.condition+" ORDER BY "+data.time+" LIMIT 5000)")
                .param(Timestamp.from(cutoff)).update();
    }
}
