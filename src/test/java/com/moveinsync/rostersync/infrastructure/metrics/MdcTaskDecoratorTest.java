package com.moveinsync.rostersync.infrastructure.metrics;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import static org.assertj.core.api.Assertions.*;

class MdcTaskDecoratorTest {
    @Test void copiesOnlyCorrelationAndRestoresTheWorkerEvenAfterFailure(){
        var decorator=new MdcTaskDecorator();MDC.put("correlationId","request");MDC.put("employeeId","caller");
        Runnable task=decorator.decorate(()->{assertThat(MDC.get("correlationId")).isEqualTo("request");assertThat(MDC.get("employeeId")).isNull();MDC.put("runId","background");throw new IllegalStateException();});
        MDC.clear();MDC.put("correlationId","worker-before");
        try{assertThatThrownBy(task::run).isInstanceOf(IllegalStateException.class);assertThat(MDC.get("correlationId")).isEqualTo("worker-before");assertThat(MDC.get("runId")).isNull();}
        finally{MDC.clear();}
    }
}
