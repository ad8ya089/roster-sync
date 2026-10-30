package com.moveinsync.rostersync.infrastructure.metrics;
import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;
import org.springframework.stereotype.Component;

@Component
public class MdcTaskDecorator implements TaskDecorator {
    public Runnable decorate(Runnable task){
        String correlation=MDC.get("correlationId");
        return ()->{var previous=MDC.getCopyOfContextMap();MDC.clear();if(correlation!=null)MDC.put("correlationId",correlation);
            try{task.run();}finally{if(previous==null)MDC.clear();else MDC.setContextMap(previous);}};
    }
}
