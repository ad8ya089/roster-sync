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
        PROCESSED("processed_event","processed_at","(sink,event_id)",""),
        RECON_ACTIONS("recon_action","finished_at","id","");
        private final String table,time,key,condition;
        Data(String table,String time,String key,String condition) {this.table=table;this.time=time;this.key=key;this.condition=condition;}
    }
    private final JdbcClient jdbc;
    public RetentionBatch(JdbcClient jdbc) {this.jdbc=jdbc;}
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public int delete(Data data,Instant cutoff) {
        if(data==Data.RECON_ACTIONS) return jdbc.sql("""
                DELETE FROM recon_action WHERE id IN (
                  SELECT a.id FROM recon_action a JOIN recon_run r ON r.id=a.run_id AND r.tenant_id=a.tenant_id
                  WHERE r.status IN ('COMPLETED','REJECTED','FAILED','PLANNED') AND r.finished_at<?
                  ORDER BY a.id LIMIT 5000)
                """).param(Timestamp.from(cutoff)).update();
        // Global maintenance, bounded to 5,000 rows and committed before the next batch.
        String selectedKey=data==Data.PROCESSED?"sink,event_id":data.key;
        return jdbc.sql("DELETE FROM "+data.table+" WHERE "+data.key+" IN (SELECT "+selectedKey+" FROM "+data.table+
                " WHERE "+data.time+"<?"+data.condition+" ORDER BY "+data.time+" LIMIT 5000)")
                .param(Timestamp.from(cutoff)).update();
    }
}
