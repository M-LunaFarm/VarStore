package kr.lunaf.varstore.testkit.paper;

import kr.lunaf.varstore.api.*;
import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Test-only console benchmark. Never install this artifact on a production network. */
public final class VarStoreBench extends JavaPlugin {
    private VarStore store;
    private VarStore.Data data;
    private final ExecutorService reporting=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"varstore-bench-report");t.setDaemon(true);return t;});
    private volatile boolean running;
    private BukkitTask timer;
    private static final int KEY_COUNT=100_000;
    @Override public void onEnable(){
        store=getServer().getServicesManager().load(VarStore.class);
        if(store==null)throw new IllegalStateException("VarStore service unavailable");
        data=store.namespace("varstorebench").network().system("load");
        Objects.requireNonNull(getCommand("varstorebench")).setExecutor(this::command);
    }
    @Override public void onDisable(){if(timer!=null)timer.cancel();reporting.shutdownNow();}
    private boolean command(CommandSender sender,Command command,String label,String[] args){
        if(!(sender instanceof ConsoleCommandSender)){sender.sendMessage("This test utility accepts console commands only.");return true;}
        if(args.length==1&&args[0].equals("seed")){
            if(running){sender.sendMessage("Benchmark already running.");return true;}
            running=true;reporting.execute(this::seed);return true;
        }
        if(args.length>=3&&args.length<=4&&args[0].equals("start")){
            try{
                int seconds=Integer.parseInt(args[1]),index=Integer.parseInt(args[2]);String phase=args.length==4?args[3]:"baseline";
                if(seconds<1||seconds>3600||index<0||index>2||!Set.of("baseline","large","transaction","hot").contains(phase))throw new IllegalArgumentException();
                if(running){sender.sendMessage("Benchmark already running.");return true;}
                if(store.state()!=StoreState.READY){sender.sendMessage("VarStore is not READY.");return true;}
                start(seconds,index,phase);return true;
            }catch(IllegalArgumentException invalid){sender.sendMessage("Usage: varstorebench start <1..3600 seconds> <0..2> [baseline|large|transaction|hot]");return true;}
        }
        return false;
    }
    private void seed(){
        long begin=System.nanoTime();
        try{
            await(store.ready());
            for(int batch=0;batch<KEY_COUNT/16;batch+=4){
                List<CompletableFuture<?>> writes=new ArrayList<>();
                for(int offset=0;offset<4&&batch+offset<KEY_COUNT/16;offset++){
                    int number=batch+offset;var builder=TransactionPlan.builder();
                    for(int j=0;j<16;j++)builder.set(data.target(VarKey.stringKey("key/"+(number*16+j))),"x".repeat(1024));
                    UUID id=UUID.nameUUIDFromBytes(("varstorebench-seed-v1/"+number).getBytes(StandardCharsets.UTF_8));
                    writes.add(store.namespace("varstorebench").execute(builder.build(),id).toCompletableFuture());
                }
                await(CompletableFuture.allOf(writes.toArray(CompletableFuture[]::new)));
            }
            await(data.setIfAbsent(VarKey.longKey("hot"),0L,UUID.nameUUIDFromBytes("varstorebench-hot-v1".getBytes(StandardCharsets.UTF_8))));
            await(data.setIfAbsent(VarKey.stringKey("large"),"x".repeat(16_384),UUID.nameUUIDFromBytes("varstorebench-large-v1".getBytes(StandardCharsets.UTF_8))));
            var builder=TransactionPlan.builder();for(int i=0;i<16;i++)builder.set(data.target(VarKey.stringKey("txn/"+i)),"x".repeat(1024));
            await(store.namespace("varstorebench").execute(builder.build(),UUID.nameUUIDFromBytes("varstorebench-txn-v1".getBytes(StandardCharsets.UTF_8))));
            getLogger().info("VARSTORE_BENCH_SEEDED keys="+KEY_COUNT+" elapsedMillis="+TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-begin));
        }catch(Throwable failure){getLogger().severe("VARSTORE_BENCH_SEED_FAILED "+failure.getClass().getSimpleName());}
        finally{running=false;}
    }
    private void start(int seconds,int serverIndex,String phase){
        running=true;Run run=new Run(seconds,serverIndex,phase);
        timer=Bukkit.getScheduler().runTaskTimer(this,()->{
            if(run.tick==0){run.startedAt=Instant.now().toString();run.start=System.nanoTime();getLogger().info("VARSTORE_BENCH_STARTED phase="+phase+" server="+serverIndex);}
            if(run.tick>=seconds*20){timer.cancel();timer=null;reporting.execute(()->finish(run));return;}
            long now=System.nanoTime();if(run.previousTick>0)run.tickIntervals.add(now-run.previousTick);run.previousTick=now;
            // Five global requests per tick, distributed over three real server threads.
            // Every ten global slots contain seven reads and three writes.
            for(int slot=0;slot<5;slot++){
                int sequence=run.tick*5+slot;
                if(sequence%3==serverIndex)submit(run,sequence,sequence%10>=7);
            }
            run.tick++;
        },1,1);
    }
    private void submit(Run run,int sequence,boolean write){
        String payload=write?payload(sequence,run.phase.equals("large")?16_384:run.phase.equals("transaction")?run.transactionValueBytes:1024):null;
        int index=Math.floorMod(Integer.rotateLeft(sequence*0x9e3779b9,13),KEY_COUNT);
        long begin=System.nanoTime();boolean mainThread=Bukkit.isPrimaryThread();CompletionStage<?> stage;
        run.outstanding.incrementAndGet();Sample sample=new Sample(sequence,write,mainThread);
        try{
            stage=switch(run.phase){
                case "baseline"->{var key=VarKey.stringKey("key/"+index);yield write?data.set(key,payload,UUID.randomUUID()):data.get(key);}
                case "large"->{var key=VarKey.stringKey("large");yield write?data.set(key,payload,UUID.randomUUID()):data.get(key);}
                case "hot"->{var key=VarKey.longKey("hot");yield write?data.increment(key,1,UUID.randomUUID()):data.get(key);}
                case "transaction"->{
                    if(write)yield store.namespace("varstorebench").execute(transaction(payload),UUID.randomUUID());
                    List<VarKey<?>> keys=new ArrayList<>();for(int i=0;i<16;i++)keys.add(VarKey.stringKey("txn/"+i));yield data.getAll(keys);
                }
                default->throw new IllegalStateException();
            };
        }catch(Throwable failure){stage=CompletableFuture.failedStage(failure);}
        sample.submission=System.nanoTime()-begin;run.samples.add(sample);
        stage.whenComplete((result,failure)->{
            sample.latency=System.nanoTime()-begin;
            Throwable cause=failure;while(cause instanceof CompletionException&&cause.getCause()!=null)cause=cause.getCause();
            sample.error=cause==null?"":cause instanceof VarStoreException v?v.code().name():cause.getClass().getSimpleName();
            if(cause==null&&!write&&result instanceof Optional<?> optional&&optional.isEmpty())sample.error="UNEXPECTED_MISSING_VALUE";
            run.outstanding.decrementAndGet();
        });
    }
    private void finish(Run run){
        try{
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);while(run.outstanding.get()>0&&System.nanoTime()<deadline)Thread.sleep(10);
            Map<String,Object> report=new LinkedHashMap<>();
            report.put("phase",run.phase);report.put("serverIndex",run.index);report.put("network",data.address(VarKey.longKey("hot")).networkId());
            report.put("paperVersion",Bukkit.getVersion());report.put("javaVersion",System.getProperty("java.version"));report.put("startedAt",run.startedAt);report.put("finishedAt",Instant.now().toString());
            report.put("provenance","API calls submitted by a repeating Bukkit main-thread task on a real Paper server");
            report.put("requestedSeconds",run.seconds);report.put("elapsedMillis",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-run.start));report.put("scheduledTicks",run.tick);
            report.put("requests",run.samples.size());report.put("allSubmissionsOnMainThread",run.samples.stream().allMatch(s->s.mainThread));report.put("outstanding",run.outstanding.get());
            report.put("submissionP50Micros",percentile(run.samples.stream().mapToLong(s->s.submission).toArray(),.5)/1000.0);report.put("submissionP95Micros",percentile(run.samples.stream().mapToLong(s->s.submission).toArray(),.95)/1000.0);report.put("submissionP99Micros",percentile(run.samples.stream().mapToLong(s->s.submission).toArray(),.99)/1000.0);
            report.put("tickIntervalP95Millis",percentile(run.tickIntervals.stream().mapToLong(Long::longValue).toArray(),.95)/1e6);report.put("tickIntervalP99Millis",percentile(run.tickIntervals.stream().mapToLong(Long::longValue).toArray(),.99)/1e6);
            report.put("reads",summary(run.samples.stream().filter(s->!s.write).toList()));report.put("writes",summary(run.samples.stream().filter(s->s.write).toList()));
            report.put("transactionKeys",run.phase.equals("transaction")?16:1);report.put("transactionEstimatedBytes",run.phase.equals("transaction")?transaction("x".repeat(run.transactionValueBytes)).estimatedBytes():0);
            Path directory=getDataFolder().toPath();Files.createDirectories(directory);String prefix="bench-"+run.index+"-"+run.phase+"-"+System.currentTimeMillis();
            Files.writeString(directory.resolve(prefix+".json"),json(report)+"\n");
            try(var out=Files.newBufferedWriter(directory.resolve(prefix+".csv"))){out.write("sequence,operation,main_thread,submission_us,latency_us,error\n");for(Sample s:run.samples)out.write(s.sequence+","+(s.write?"write":"read")+","+s.mainThread+","+s.submission/1000.0+","+s.latency/1000.0+","+s.error+"\n");}
            getLogger().info("VARSTORE_BENCH_COMPLETED phase="+run.phase+" server="+run.index+" report="+prefix+".json requests="+run.samples.size());
        }catch(Throwable failure){getLogger().severe("VARSTORE_BENCH_REPORT_FAILED "+failure.getClass().getSimpleName());}
        finally{running=false;}
    }
    private TransactionPlan transaction(String value){var builder=TransactionPlan.builder();for(int i=0;i<16;i++)builder.set(data.target(VarKey.stringKey("txn/"+i)),value);return builder.build();}
    private static String payload(int sequence,int size){SplittableRandom random=new SplittableRandom(sequence);char[] text=new char[size];String alphabet="abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";for(int i=0;i<size;i++)text[i]=alphabet.charAt(random.nextInt(alphabet.length()));return new String(text);}
    private Map<String,Object> summary(List<Sample> samples){
        long[] times=samples.stream().filter(s->s.error.isEmpty()).mapToLong(s->s.latency).toArray();Map<String,Long> errors=new TreeMap<>();for(Sample sample:samples)if(!sample.error.isEmpty())errors.merge(sample.error,1L,Long::sum);
        return Map.of("count",samples.size(),"errors",errors,"successP50Millis",percentile(times,.5)/1e6,"successP95Millis",percentile(times,.95)/1e6,"successP99Millis",percentile(times,.99)/1e6);
    }
    private final class Run {
        final int seconds,index,transactionValueBytes;final String phase;final List<Sample> samples=new ArrayList<>();final List<Long> tickIntervals=new ArrayList<>();final AtomicInteger outstanding=new AtomicInteger();
        int tick;long start,previousTick;String startedAt;
        Run(int seconds,int index,String phase){this.seconds=seconds;this.index=index;this.phase=phase;transactionValueBytes=(int)((65_536-transaction("").estimatedBytes())/16);}
    }
    private static final class Sample {final int sequence;final boolean write,mainThread;volatile long submission,latency;volatile String error="";Sample(int sequence,boolean write,boolean mainThread){this.sequence=sequence;this.write=write;this.mainThread=mainThread;}}
    private static <T>T await(CompletionStage<T> stage)throws Exception{return stage.toCompletableFuture().get(30,TimeUnit.SECONDS);}
    private static long percentile(long[] values,double p){if(values.length==0)return 0;Arrays.sort(values);return values[Math.max(0,(int)Math.ceil(values.length*p)-1)];}
    private static String json(Object value){
        if(value==null)return "null";if(value instanceof Number||value instanceof Boolean)return value.toString();
        if(value instanceof Map<?,?> map){List<String> items=new ArrayList<>();map.forEach((k,v)->items.add(json(k.toString())+":"+json(v)));return "{"+String.join(",",items)+"}";}
        if(value instanceof Collection<?> list)return "["+String.join(",",list.stream().map(VarStoreBench::json).toList())+"]";
        return "\""+value.toString().replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r")+"\"";
    }
}
