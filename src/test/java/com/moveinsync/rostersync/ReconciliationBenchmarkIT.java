package com.moveinsync.rostersync;

import com.moveinsync.rostersync.application.recon.*;
import com.moveinsync.rostersync.domain.model.Role;
import java.lang.management.ManagementFactory;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@Tag("benchmark") @Timeout(600)
class ReconciliationBenchmarkIT extends AbstractReconciliationIntegrationTest {
    private static final UUID BENCH=UUID.fromString("00000000-0000-0000-0000-000000000003");
    @org.springframework.beans.factory.annotation.Autowired com.moveinsync.rostersync.config.RosterProperties properties;
    @org.springframework.beans.factory.annotation.Autowired ReconciliationChunkApplier chunks;
    @Test void exact200000RowSnapshotFits256MiBAnd60Seconds() throws Exception {
        var memory=ManagementFactory.getMemoryMXBean();
        assertThat(memory.getHeapMemoryUsage().getMax()).isEqualTo(256L*1024*1024);
        jdbc.update("INSERT INTO tenant(id,code,name) VALUES (?,'bench','Benchmark')",BENCH);
        jdbc.update("INSERT INTO tenant_settings(tenant_id) VALUES (?)",BENCH);
        jdbc.update("INSERT INTO office(tenant_id,code,name) VALUES (?,'BENCH-1','Benchmark')",BENCH);
        seed.seed(BENCH,200000);
        Path file=temporary.resolve("exact-200000.csv");
        try(var output=Files.newOutputStream(file)) {
            assertThat(exporter.write(BENCH,output,RosterFileGenerator.Spec.unchanged()).written()).isEqualTo(200000);
        }
        var peak=new AtomicLong(memory.getHeapMemoryUsage().getUsed());
        var sampler=Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("exact-200k-heap").factory());
        sampler.scheduleAtFixedRate(()->peak.accumulateAndGet(memory.getHeapMemoryUsage().getUsed(),Math::max),0,50,TimeUnit.MILLISECONDS);
        try {
            long started=System.nanoTime();
            var run=awaitRun(uploads.upload(BENCH,"benchmark",new ReconciliationFlowIT.PathUpload(file),Instant.now(),200000,true).runId());
            long wallMs=(System.nanoTime()-started)/1_000_000;
            assertThat(run.status()).isEqualTo("PLANNED");
            assertThat(run.rows().valid()).isEqualTo(200000);
            assertThat(run.rows().rejected()).isZero();
            assertThat(run.plan().unchanged()).isEqualTo(200000);
            assertThat(run.brake().tripped()).isFalse();
            assertThat(wallMs).isLessThan(60000);
            var report=Map.of("rows",200000,"heapLimitMiB",256,"wallMs",wallMs,"diffMs",run.timings().diffMs(),"sampledPeakHeapMiB",peak.get()/1048576.0);
            Files.writeString(Path.of("target/final-exact-200k.json"),mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report));
            System.out.println("EXACT_200K "+mapper.writeValueAsString(report));
        } finally {sampler.shutdownNow();}
    }
    @Test void medianOfThree200kRunsWithA256MiBHeap() throws Exception {
        var memory=ManagementFactory.getMemoryMXBean();
        assertThat(memory.getHeapMemoryUsage().getMax()).as("benchmark must run with -Xmx256m").isEqualTo(256L*1024*1024);
        var results=new ArrayList<Sample>();
        var chunkNanos=new ConcurrentLinkedQueue<Long>();
        org.aopalliance.intercept.MethodInterceptor timing=invocation->{
            if(!invocation.getMethod().getName().equals("applyNext"))return invocation.proceed();
            long start=System.nanoTime();Object result=invocation.proceed();
            if((int)result>0)chunkNanos.add(System.nanoTime()-start);
            return result;
        };
        // Outermost advice includes transaction commit. No timing collection is added to production.
        var proxy=(org.springframework.aop.framework.Advised)chunks;
        proxy.addAdvice(0,timing);
        try {
        for(int iteration=1;iteration<=3;iteration++){
            resetDatabase();seedUsersAndOffices();
            jdbc.update("INSERT INTO tenant(id,code,name) VALUES (?,'bench','Benchmark')",BENCH);
            jdbc.update("INSERT INTO tenant_settings(tenant_id) VALUES (?)",BENCH);
            jdbc.update("INSERT INTO office(tenant_id,code,name) VALUES (?,'BENCH-1','Benchmark')",BENCH);
            long seedStart=System.nanoTime();seed.seed(BENCH,200000);long seedMs=(System.nanoTime()-seedStart)/1_000_000;
            Path file=temporary.resolve("benchmark-"+iteration+".csv");RosterFileGenerator.Expected truth;
            try(var output=Files.newOutputStream(file)){
                truth=exporter.write(BENCH,output,new RosterFileGenerator.Spec(null,new BigDecimal("1"),new BigDecimal("0.5"),new BigDecimal("0.1"),20261030));
            }
            System.gc();long baseline=memory.getHeapMemoryUsage().getUsed();var peak=new AtomicLong(baseline);
            var sampler=Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("benchmark-heap").factory());
            sampler.scheduleAtFixedRate(()->peak.accumulateAndGet(memory.getHeapMemoryUsage().getUsed(),Math::max),0,50,TimeUnit.MILLISECONDS);
            try {
                long started=System.nanoTime();var dry=awaitRun(uploads.upload(BENCH,"benchmark",new ReconciliationFlowIT.PathUpload(file),Instant.now(),null,true).runId());
                long wallMs=(System.nanoTime()-started)/1_000_000;assertThat(dry.status()).as("run %s failure=%s",dry.runId(),dry.failureReason()).isEqualTo("PLANNED");assertPlan(dry,truth);
                assertThat(dry.timings().diffMs()).isLessThan(60000);assertThat(wallMs).isLessThan(60000);
                var applied=awaitRun(uploads.upload(BENCH,"benchmark",new ReconciliationFlowIT.PathUpload(file),Instant.now(),null,false).runId());
                assertThat(applied.status()).isEqualTo("COMPLETED");assertPlan(applied,truth);
                assertThat(applied.applied()).isEqualTo(truth.updates()+truth.creates()+truth.dropped());
                assertThat(jdbc.queryForObject("SELECT count(*) FROM employee WHERE tenant_id=? AND status='ACTIVE'",Long.class,BENCH)).isEqualTo(truth.written());
                assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE tenant_id=? AND event_type LIKE 'Employee%'",Long.class,BENCH)).isEqualTo(applied.applied());
                results.add(new Sample(iteration,seedMs,truth.written(),truth.updates(),truth.dropped(),truth.creates(),wallMs,dry.timings().diffMs(),applied.timings().applyMs(),peak.get()/1048576.0,(peak.get()-baseline)/1048576.0));
            }finally{sampler.shutdownNow();}
        }
        } finally { proxy.removeAdvice(timing); }
        var sortedChunks=chunkNanos.stream().mapToDouble(value->value/1_000_000.0).sorted().toArray();
        assertThat(sortedChunks).isNotEmpty();
        double chunkP50=percentile(sortedChunks,.5),chunkP99=percentile(sortedChunks,.99),chunkMax=sortedChunks[sortedChunks.length-1];
        var report=new LinkedHashMap<String,Object>();report.put("heapLimitMiB",256);report.put("java",System.getProperty("java.runtime.version"));report.put("os",System.getProperty("os.name")+" "+System.getProperty("os.version"));
        report.put("chunkP50Ms",chunkP50);report.put("chunkP99Ms",chunkP99);report.put("chunkMaximumMs",chunkMax);
        report.put("chunkCount",sortedChunks.length);report.put("chunkDurationsMs",chunkNanos.stream().map(value->value/1_000_000.0).toList());
        report.put("chunkMethod","nearest-rank over all nonempty committed chunks, including cold chunks; outer advice includes commit");
        report.put("chunkSize",properties.reconciliation().chunkSize());report.put("maxChunkMs",applier.longestChunkMillis());
        report.put("logicalProcessors",Runtime.getRuntime().availableProcessors());report.put("samples",results);
        var machine=machineSpec();report.put("machine",machine);
        report.put("medianDiffWallMs",median(results.stream().mapToDouble(Sample::diffWallMs).toArray()));report.put("medianDiffMs",median(results.stream().mapToDouble(Sample::diffMs).toArray()));
        report.put("medianApplyMs",median(results.stream().mapToDouble(Sample::applyMs).toArray()));report.put("medianPeakHeapMiB",median(results.stream().mapToDouble(Sample::peakHeapMiB).toArray()));
        Files.writeString(Path.of("target/phase8-benchmark.json"),mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        System.out.println("BENCHMARK_MEDIAN "+mapper.writeValueAsString(report));
        System.out.printf(Locale.ENGLISH,"| %s | %s | %.2f GiB | %s | %s | 200,000 | 256 MiB | %.0f ms | %.0f ms | %.1f MiB |%n",
                machine.get("cpu"),machine.get("logicalCores"),((Number)machine.get("ramBytes")).doubleValue()/1073741824,
                report.get("os"),report.get("java"),report.get("medianDiffWallMs"),report.get("medianApplyMs"),report.get("medianPeakHeapMiB"));
        assertThat(chunkP99).as("chunk p99 below 1s").isLessThan(1000);
        assertThat(chunkMax).as("chunk max below half the 5s lock_timeout").isLessThan(2500);
    }
    private ReconciliationQueryService.RunView awaitRun(UUID id){
        await().atMost(Duration.ofSeconds(150)).until(()->Set.of("PLANNED","COMPLETED","FAILED","AWAITING_APPROVAL").contains(runs.get(BENCH,id).status()) && !queue.active(id));
        return queries.get(BENCH,id,Role.OPERATOR,"benchmark");
    }
    private static void assertPlan(ReconciliationQueryService.RunView run,RosterFileGenerator.Expected truth){
        assertThat(run.rows().valid()).isEqualTo(truth.written());assertThat(run.rows().rejected()).isZero();
        assertThat(run.plan().updates()).isEqualTo(truth.updates());assertThat(run.plan().creates()).isEqualTo(truth.creates());assertThat(run.plan().deactivations()).isEqualTo(truth.dropped());
        assertThat(run.plan().unchanged()).isEqualTo(truth.existing()-truth.dropped()-truth.updates());assertThat(run.brake().tripped()).isFalse();
    }
    private static double median(double[] values){Arrays.sort(values);return values[values.length/2];}
    private static double percentile(double[] sorted,double percentile){return sorted[(int)Math.ceil(percentile*sorted.length)-1];}
    private Map<String,Object> machineSpec(){
        var result=new LinkedHashMap<String,Object>();result.put("logicalCores",Runtime.getRuntime().availableProcessors());
        result.put("ramBytes",((com.sun.management.OperatingSystemMXBean)ManagementFactory.getOperatingSystemMXBean()).getTotalMemorySize());
        result.put("cpu",System.getenv().getOrDefault("PROCESSOR_IDENTIFIER",System.getProperty("os.arch")));
        try{
            if(System.getProperty("os.name").startsWith("Windows")){
                var cpu=mapper.readTree(command("powershell.exe","-NoProfile","-NonInteractive","-WindowStyle","Hidden","-Command",
                        "Get-CimInstance Win32_Processor | Select-Object Name,NumberOfCores,NumberOfLogicalProcessors | ConvertTo-Json -Compress"));
                result.put("cpu",cpu.get("Name").asText());result.put("physicalCores",cpu.get("NumberOfCores").asInt());
            }else if(Files.exists(Path.of("/proc/cpuinfo"))){
                result.put("cpu",Files.readAllLines(Path.of("/proc/cpuinfo")).stream().filter(line->line.startsWith("model name")).findFirst().map(line->line.substring(line.indexOf(':')+1).strip()).orElse(System.getProperty("os.arch")));
            }
            String[] docker=command(System.getProperty("os.name").startsWith("Windows")?"docker.exe":"docker","info","--format","{{.MemTotal}} {{.NCPU}}").split("\\s+");
            result.put("dockerMemoryBytes",Long.parseLong(docker[0]));result.put("dockerLogicalCpus",Integer.parseInt(docker[1]));
        }catch(Exception unavailable){result.put("additionalHardwareMetadata","unavailable");}
        return result;
    }
    private static String command(String... arguments) throws Exception {
        var process=new ProcessBuilder(arguments).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        if(!process.waitFor(10,TimeUnit.SECONDS)){process.destroyForcibly();throw new IllegalStateException("Hardware metadata timeout");}
        if(process.exitValue()!=0)throw new IllegalStateException("Hardware metadata unavailable");
        return new String(process.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).strip();
    }
    private record Sample(int iteration,long seedMs,long written,long updates,long deactivations,long creates,long diffWallMs,long diffMs,long applyMs,double peakHeapMiB,double heapDeltaMiB) { }
}
