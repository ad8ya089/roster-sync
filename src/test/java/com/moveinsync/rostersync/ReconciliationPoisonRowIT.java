package com.moveinsync.rostersync;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ReconciliationPoisonRowIT extends AbstractReconciliationIntegrationTest {
    @Test void poisonRowIsRejectedWithoutFailingTheRunOrLosingTheNextRow() throws Exception {
        var id=upload(header()+row("POISON1",1,"S1").replace("Fixture Person","Fixture\u0000Person")+row("AFTER1",1,"S1"),true);
        var result=finished(id);
        assertThat(result.status()).isEqualTo("PLANNED");
        assertThat(jdbc.queryForObject("SELECT rows_rejected FROM recon_run WHERE id=?",Long.class,id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM recon_action WHERE run_id=? AND external_id='AFTER1'",Long.class,id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT error_codes FROM record_rejection WHERE run_id=?",String.class,id)).contains("INVALID_FORMAT");
    }
}
