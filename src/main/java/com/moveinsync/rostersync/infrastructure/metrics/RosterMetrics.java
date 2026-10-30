package com.moveinsync.rostersync.infrastructure.metrics;

import com.moveinsync.rostersync.api.error.ErrorCode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.*;

/** Finite labels only. Tenant-specific operational state belongs in SQL, not metric tags. */
@Component
public class RosterMetrics {
    private final MeterRegistry registry;private final Clock clock;private final Instant since;
    // Aggregate p99 across outcomes without exporting a second copy of every observation.
    private final SimpleMeterRegistry summaryRegistry=new SimpleMeterRegistry();
    private final Timer allApplies=Timer.builder("roster.apply.duration").publishPercentiles(.5,.99).register(summaryRegistry);
    public RosterMetrics(MeterRegistry registry,Clock clock){this.registry=registry;this.clock=clock;since=clock.instant();registerCatalogue();}
    private void registerCatalogue(){
        // Finite zero-valued series make an idle/restarted instance observable without
        // inventing events. Registering a timer does not record a latency/lag sample.
        for(var outcome:com.moveinsync.rostersync.domain.model.IngestOutcome.values()){
            applyTimer(outcome.metricTag());
            for(String type:List.of("CREATE","UPDATE","DEACTIVATE","UNKNOWN"))registry.counter("roster.events","outcome",outcome.metricTag(),"type",type);
        }
        lagTimer();for(String reason:List.of("lock_timeout","unique_race","serialization"))registry.counter("roster.apply.retries","reason",reason);
        for(var code:ErrorCode.values())for(String source:List.of("api","file"))registry.counter("roster.rejections","reason",code.name(),"source",source);
        for(String reason:List.of("MALFORMED_CSV","APPLY_FAILED"))registry.counter("roster.rejections","reason",reason,"source","file");
        registry.counter("side.effects.bookings.cancelled");registry.counter("side.effects.seats.freed");registry.counter("side.effects.skipped","reason","reactivated");
        for(String status:List.of("PLANNED","AWAITING_APPROVAL","COMPLETED","REJECTED","FAILED"))registry.counter("recon.runs","status",status);
        for(String reason:List.of("ROW_COUNT_MISMATCH","MISSING_RATIO","MISSING_COUNT"))registry.counter("recon.brake.tripped","reason",reason);
        for(var action:com.moveinsync.rostersync.domain.recon.ActionType.values())for(String result:List.of("APPLIED","SUPERSEDED","SKIPPED","FAILED"))registry.counter("recon.actions","action",action.name(),"result",result);
        for(String phase:List.of("parse","diff","apply"))registry.timer("recon.phase.duration","phase",phase);
        registry.counter("recon.rows.rejected");
        for(var type:com.moveinsync.rostersync.domain.outbox.OutboxEventType.values())registry.counter("outbox.publish.failures","sink","executor","event_type",type.wireName());
    }
    public void registerSink(String sink,String type){for(String name:List.of("outbox.published","outbox.publish.failures","outbox.retries"))registry.counter(name,"sink",sink,"event_type",type);}
    private Timer applyTimer(String outcome){return Timer.builder("roster.apply.duration").tag("outcome",outcome).publishPercentiles(.5,.99).publishPercentileHistogram().register(registry);}
    private Timer lagTimer(){return Timer.builder("roster.ingest.lag").publishPercentiles(.5,.99).publishPercentileHistogram().register(registry);}
    public void event(String outcome,String type,long nanos,Instant occurredAt) {
        registry.counter("roster.events","outcome",outcome,"type",type).increment();
        applyTimer(outcome).record(nanos,TimeUnit.NANOSECONDS);
        allApplies.record(nanos,TimeUnit.NANOSECONDS);
        if(occurredAt!=null){var lag=Duration.between(occurredAt,clock.instant());if(!lag.isNegative())
            lagTimer().record(lag);}
    }
    public void retry(String reason){registry.counter("roster.apply.retries","reason",reason).increment();}
    public void rejection(String code,String source) {
        String safe=Arrays.stream(ErrorCode.values()).anyMatch(value->value.name().equals(code)) || Set.of("MALFORMED_CSV","APPLY_FAILED").contains(code)?code:"INVALID_FORMAT";
        registry.counter("roster.rejections","reason",safe,"source",source).increment();
    }
    public void fileRowRejected(){registry.counter("recon.rows.rejected").increment();}
    public void sinkPublished(String sink,String type){registry.counter("outbox.published","sink",sink,"event_type",type).increment();}
    public void sinkFailed(String sink,String type){registry.counter("outbox.publish.failures","sink",sink,"event_type",type).increment();}
    public void sinkRetry(String sink,String type){registry.counter("outbox.retries","sink",sink,"event_type",type).increment();}
    public void sideEffects(int bookings,int seats){registry.counter("side.effects.bookings.cancelled").increment(bookings);registry.counter("side.effects.seats.freed").increment(seats);}
    public void skipped(String reason){registry.counter("side.effects.skipped","reason",reason).increment();}
    public void run(String status){
        // Count operator decisions and final outcomes, not worker progress transitions.
        if(Set.of("PLANNED","AWAITING_APPROVAL","COMPLETED","REJECTED","FAILED").contains(status))
            committed(()->registry.counter("recon.runs","status",status).increment());
    }
    public void brake(String reason){committed(()->registry.counter("recon.brake.tripped","reason",reason).increment());}
    public void action(String action,String result){committed(()->registry.counter("recon.actions","action",action,"result",result).increment());}
    public void phase(String phase,long nanos){registry.timer("recon.phase.duration","phase",phase).record(nanos,TimeUnit.NANOSECONDS);}
    public Instant since(){return since;}
    public double duplicates(){return sum("roster.events","outcome","duplicate");}
    public double lockRetries(){return sum("roster.apply.retries","reason","lock_timeout");}
    public double publishFailures(){return registry.find("outbox.publish.failures").counters().stream().mapToDouble(Counter::count).sum();}
    public Double p99ApplyMs(){return percentile(allApplies,TimeUnit.MILLISECONDS);}
    public Double p99LagSeconds(){return percentile(registry.find("roster.ingest.lag").timer(),TimeUnit.SECONDS);}
    private double sum(String name,String tag,String value){return registry.find(name).tag(tag,value).counters().stream().mapToDouble(Counter::count).sum();}
    private static Double percentile(Timer timer,TimeUnit unit){if(timer==null || timer.count()==0)return null;return Arrays.stream(timer.takeSnapshot().percentileValues()).filter(value->value.percentile()==.99).map(value->value.value(unit)).findFirst().orElse(null);}
    public static void committed(Runnable action){if(!TransactionSynchronizationManager.isSynchronizationActive()){action.run();return;}
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCommit(){action.run();}});}
    @jakarta.annotation.PreDestroy public void close(){summaryRegistry.close();}
}
