package com.moveinsync.rostersync;

import com.moveinsync.rostersync.application.recon.RosterFileGenerator;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Timeout(30)
class DemoDataTimeoutIT extends AbstractReconciliationIntegrationTest {
    @MockitoSpyBean JdbcTemplate template;
    @Test void bulkTransactionsUseFiveMinutesWithoutChangingThePooledDefault() throws Exception {
        var observed=new AtomicInteger();
        doAnswer(call->{call.callRealMethod();assertThat(template.queryForObject("SHOW statement_timeout",String.class)).isEqualTo("5min");observed.incrementAndGet();return null;})
                .when(template).execute("SET LOCAL statement_timeout = '5min'");
        seed.seed(JwtTestTokens.ACME,2);
        assertThat(template.queryForObject("SHOW statement_timeout",String.class)).isEqualTo("30s");
        exporter.write(JwtTestTokens.ACME,new ByteArrayOutputStream(),RosterFileGenerator.Spec.unchanged());
        assertThat(observed.get()).isEqualTo(2);
        assertThat(template.queryForObject("SHOW statement_timeout",String.class)).isEqualTo("30s");
    }
}
