package com.moveinsync.rostersync;
import com.moveinsync.rostersync.infrastructure.fault.*;
import com.moveinsync.rostersync.domain.validation.OfficeLookup;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Timeout(30)
class TransactionAtomicityIT extends AbstractIngestionIntegrationTest {
    @MockitoBean FaultInjector faults;
    @MockitoSpyBean OfficeLookup offices;
    @Autowired PlatformTransactionManager transactions;
    @Autowired com.moveinsync.rostersync.infrastructure.persistence.OfficeRepository officeRows;

    @Test void refreshesAnAlreadyManagedOfficeBeforeCheckingItsActiveFlag() {
        new org.springframework.transaction.support.TransactionTemplate(transactions).execute(status->{
            var cached=officeRows.findByTenantIdAndCode(JwtTestTokens.ACME,"BLR-1").orElseThrow();
            assertThat(cached.isActive()).isTrue();
            jdbc.update("UPDATE office SET active=false WHERE tenant_id=? AND code='BLR-1'",JwtTestTokens.ACME);
            assertThat(cached.isActive()).isTrue();
            assertThat(officeRows.refreshPlain(JwtTestTokens.ACME,cached).isActive()).isFalse();
            status.setRollbackOnly(); return null;
        });
    }

    @Test void oneJpaTransactionRollsBackEmployeeOutboxAndReceiptTogether() throws Exception {
        assertThat(transactions).isInstanceOf(JpaTransactionManager.class);
        doAnswer(call->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(version("TX1")).isEqualTo(1);
            assertThat(count("outbox_event")).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT outcome FROM hr_event_receipt",String.class)).isEqualTo("PROCESSING");
            throw new SimulatedCrashException();
        }).when(faults).hit(FaultPoint.APPLY_AFTER_EMPLOYEE_WRITE);
        assertThat(send(event("TX1",1,"CREATE","BLR-1","S1")).summary().retryableError()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM employee WHERE tenant_id=? AND external_id='TX1'",Long.class,JwtTestTokens.ACME)).isZero();
        assertThat(count("outbox_event")).isZero(); assertThat(count("hr_event_receipt")).isZero();
    }
    @Test void forcedRollbackAlsoRestoresAnExistingEmployeeExactly() throws Exception {
        send(event("TX2",1,"CREATE","BLR-1","S1"));
        String before=employeeBytes("TX2"); long receipts=count("hr_event_receipt"), outbox=count("outbox_event");
        doThrow(new SimulatedCrashException()).when(faults).hit(FaultPoint.APPLY_AFTER_EMPLOYEE_WRITE);
        assertThat(send(event("TX2",2,"UPDATE","BLR-2","S2")).summary().retryableError()).isEqualTo(1);
        assertThat(employeeBytes("TX2")).isEqualTo(before);
        assertThat(count("outbox_event")).isEqualTo(outbox); assertThat(count("hr_event_receipt")).isEqualTo(receipts);
    }
    @Test void inactiveOfficeFoundInsideTransactionRejectsAndRollsBackProvisionalReceipt() throws Exception {
        doReturn(OfficeLookup.Status.ACTIVE).when(offices).lookup(JwtTestTokens.ACME,"BLR-1");
        jdbc.update("UPDATE office SET active=false WHERE tenant_id=? AND code='BLR-1'",JwtTestTokens.ACME);
        var result=send(event("TX3",1,"CREATE","BLR-1","S1"));
        assertThat(result.summary().rejected()).isEqualTo(1);
        assertThat(result.results().getFirst().errors()).anyMatch(error->error.code().equals("INACTIVE_OFFICE"));
        assertThat(count("hr_event_receipt")).isZero(); assertThat(count("outbox_event")).isZero();
        assertThat(count("record_rejection")).isEqualTo(1);
    }
}
