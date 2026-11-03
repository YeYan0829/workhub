package dev.workhub.governance;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workhub.agent.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.test.util.ReflectionTestUtils;

public class ModelAdmissionIT {
  static final ObjectMapper JSON=new ObjectMapper();
  static final List<Map<String,Object>> GRANTS=new CopyOnWriteArrayList<>();
  static int port(){return Integer.parseInt(System.getenv().getOrDefault("MODEL_REDIS_PORT","26379"));}
  static ModelAdmission gate(String scope,double rate,int burst){return new ModelAdmission(true,scope,rate,burst,"127.0.0.1",port(),"");}
  static StringRedisTemplate redis(ModelAdmission gate){return (StringRedisTemplate)ReflectionTestUtils.getField(gate,"redis");}
  static String key(String scope){return "workhub:model:{"+scope+"}";}
  public static void initialize(ModelAdmission gate,String scope,double rate,int burst)throws Exception {
    var script=new DefaultRedisScript<List>();script.setLocation(new ClassPathResource("model/admission.lua"));script.setResultType(List.class);
    long recovery=(long)Math.ceil(1000*burst/rate);
    var result=redis(gate).execute(script,List.of(key(scope),key(scope)+":init"),"initialize","v1|"+rate+"|"+burst,""+rate,""+burst,""+recovery,UUID.randomUUID().toString());
    assertThat(result.get(0).toString()).isEqualTo("INITIALIZED");
    // Known synthetic recovery only. Application never changes timestamps or grants full tokens.
    Thread.sleep(recovery+2);
  }
  static ModelAdmission.Decision permit(ModelAdmission gate)throws Exception {
    long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
    while(true){var d=gate.acquire();if(d.permitted()){GRANTS.add(Map.of("pid",ProcessHandle.current().pid(),"permitAt",d.at(),"ticket",d.ticket(),"validWhenReceived",d.valid()));if(d.valid())return d;gate.outcome(d,0,0);continue;}assertThat(System.nanoTime()).isLessThan(deadline);Thread.sleep(Math.max(1,Math.min(20,d.delay())));}
  }
  @RepeatedTest(3) void missingStateAndMismatchedContractsNeverAdmit()throws Exception {
    String scope="test-"+UUID.randomUUID();var gate=gate(scope,1000,1);
    try{assertThatThrownBy(gate::acquire).hasFieldOrPropertyWithValue("code","MODEL_ADMISSION_MISSING");initialize(gate,scope,1000,1);
      var other=gate(scope,2,1);try{assertThatThrownBy(other::acquire).hasFieldOrPropertyWithValue("code","MODEL_ADMISSION_CONFIG");}finally{other.close();}
      redis(gate).delete(key(scope));assertThatThrownBy(gate::acquire).hasFieldOrPropertyWithValue("code","MODEL_ADMISSION_MISSING");
      initialize(gate,scope,1000,1); // reset starts empty, not at burst
      assertThat(redis(gate).opsForHash().get(key(scope),"tokens")).isEqualTo("0");
    }finally{gate.close();}
  }
  @Test void startupWarmupDoesNotChangeCooldownCircuitOrProbe()throws Exception {
    for(String state:List.of("COOLDOWN","OPEN","HALF_OPEN")) {
      String scope="warmup-"+UUID.randomUUID();var a=gate(scope,1000,10);
      try {
        initialize(a,scope,1000,10);
        if(state.equals("COOLDOWN"))a.outcome(permit(a),429,120000);
        else {
          for(int i=0;i<5;i++)a.outcome(permit(a),503,0);
          if(state.equals("HALF_OPEN")) {
            redis(a).opsForHash().putAll(key(scope),Map.of("openUntil","0","at","0"));
            permit(a); // actual single probe after advancing only the fixture clock
          }
        }
        var before=redis(a).opsForHash().entries(key(scope));
        var b=gate(scope,1000,10);
        try {
          assertThat(redis(a).opsForHash().entries(key(scope))).isEqualTo(before);
          assertThat(redis(a).hasKey(key(scope)+":permit:warmup")).isFalse();
        }finally{b.close();}
      }finally{a.close();}
    }
  }
  @RepeatedTest(3) void cooldownUsesMaximumAndMalformedHintRequiresRecovery()throws Exception {
    String scope="test-"+UUID.randomUUID();var a=gate(scope,1000,4);var b=gate(scope,1000,4);
    try{initialize(a,scope,1000,4);var longHint=permit(a);var shortHint=permit(b);var invalid=permit(a);
      var executor=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);long began=System.currentTimeMillis();
      try{var longResult=executor.submit(()->{start.await();a.outcome(longHint,429,120000);return true;});
        var shortResult=executor.submit(()->{start.await();b.outcome(shortHint,429,30000);return true;});start.countDown();
        assertThat(longResult.get(3,TimeUnit.SECONDS)).isTrue();assertThat(shortResult.get(3,TimeUnit.SECONDS)).isTrue();
      }finally{executor.shutdownNow();}
      long until=Long.parseLong(redis(a).opsForHash().get(key(scope),"cooldown").toString());assertThat(until).isBetween(began+120000,System.currentTimeMillis()+120000);
      b.outcome(shortHint,429,30000);assertThat(redis(a).opsForHash().get(key(scope),"cooldown").toString()).isEqualTo(""+until);
      assertThat(b.acquire().reason()).isEqualTo("COOLDOWN");a.outcome(invalid,429,-1);
      assertThatThrownBy(b::acquire).hasFieldOrPropertyWithValue("code","MODEL_ADMISSION_RECOVERY_REQUIRED");
    }finally{a.close();b.close();}
  }
  @Test void fiveFailuresOpenDuplicateResponsesAndOldEpochCannotClose()throws Exception {
    String scope="test-"+UUID.randomUUID();var a=gate(scope,1000,10);var b=gate(scope,1000,10);
    try{initialize(a,scope,1000,10);var late=permit(a);
      for(int i=0;i<5;i++){var p=permit(a);a.outcome(p,503,0);a.outcome(p,503,0);if(i<4)assertThat(redis(a).opsForHash().get(key(scope),"circuit")).isEqualTo("CLOSED");}
      assertThat(b.acquire().reason()).isEqualTo("OPEN");a.outcome(late,200,0);assertThat(b.acquire().reason()).isEqualTo("OPEN");
      redis(a).opsForHash().putAll(key(scope),Map.of("openUntil","0","at","0")); // advance synthetic circuit time, no 30-second performance claim
      var probe=permit(b);assertThat(a.acquire().reason()).isEqualTo("PROBE_BUSY");
      b.outcome(probe,200,0);assertThat(permit(a).permitted()).isTrue();
    }finally{a.close();b.close();}
  }
  @Test void expiredProbeReopensInsteadOfAdmittingAnotherProbe()throws Exception {
    String scope="test-"+UUID.randomUUID();var a=gate(scope,1000,1);
    try{initialize(a,scope,1000,1);redis(a).opsForHash().putAll(key(scope),Map.of("circuit","OPEN","openUntil","0","at","0"));
      var probe=permit(a);redis(a).opsForHash().put(key(scope),"probeUntil","0");assertThat(a.acquire().reason()).isEqualTo("OPEN");a.outcome(probe,200,0);assertThat(a.acquire().reason()).isEqualTo("OPEN");
    }finally{a.close();}
  }
  @RepeatedTest(3) void disconnectedRedisFailsWithinTwoSeconds(){
    var gate=new ModelAdmission(true,"unavailable",2,1,"127.0.0.1",1,"");long began=System.nanoTime();
    try{assertThatThrownBy(gate::acquire).hasFieldOrPropertyWithValue("code","MODEL_ADMISSION_UNAVAILABLE");assertThat((System.nanoTime()-began)/1_000_000).isLessThan(2000);}finally{gate.close();}
  }
  @RepeatedTest(3) void twoJvmsShareRateAndSendTwelveRealHttpRequests()throws Exception {
    String scope="test-"+UUID.randomUUID();var gate=gate(scope,2,1);
    Path dir=Path.of("target/model-admission",scope).toAbsolutePath();Files.createDirectories(dir);
    try(var server=new MockWebServer()){
      initialize(gate,scope,2,1);
      var provider429=new java.util.concurrent.atomic.AtomicInteger();
      server.setDispatcher(new Dispatcher(){double tokens=2;long at=System.nanoTime();
        public synchronized MockResponse dispatch(RecordedRequest r){long now=System.nanoTime();tokens=Math.min(2,tokens+(now-at)*2.5/1_000_000_000.0);at=now;
          if(tokens<1){provider429.incrementAndGet();return new MockResponse().setResponseCode(429);}tokens--;return new MockResponse().setBody("ok");}});server.start();
      var children=new ArrayList<Process>();try{
        for(int i=0;i<2;i++)children.add(new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/java").toString(),"-Xmx192m","-XX:ActiveProcessorCount=1","-cp",System.getProperty("java.class.path"),ModelAdmissionIT.class.getName(),scope,dir.toString(),""+i,server.url("/model").toString()).redirectErrorStream(true).redirectOutput(dir.resolve("child-"+i+".log").toFile()).start());
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);while(!(Files.exists(dir.resolve("ready-0"))&&Files.exists(dir.resolve("ready-1")))){assertThat(System.nanoTime()).isLessThan(until);Thread.sleep(20);}Files.createFile(dir.resolve("go"));
        for(var child:children){assertThat(child.waitFor(30,TimeUnit.SECONDS)).isTrue();assertThat(child.exitValue()).isZero();}
        var events=new ArrayList<Map<String,Object>>();for(int i=0;i<2;i++)events.addAll(JSON.readValue(dir.resolve("events-"+i+".json").toFile(),List.class));
        var grants=new ArrayList<Map<String,Object>>();for(int i=0;i<2;i++)grants.addAll(JSON.readValue(dir.resolve("permits-"+i+".json").toFile(),List.class));
        grants.sort(Comparator.comparingLong(e->((Number)e.get("permitAt")).longValue()));
        long violations=0;for(int i=0;i<grants.size();i++)for(int j=i;j<grants.size();j++){
          long span=((Number)grants.get(j).get("permitAt")).longValue()-((Number)grants.get(i).get("permitAt")).longValue();
          if(j-i+1>1+2*span/1000.0+0.002)violations++; // at most one millisecond timestamp tolerance, never an extra request
        }
        JSON.writeValue(dir.resolve("summary.json").toFile(),Map.of("events",events,"permitsIncludingUnused",grants,"intervalViolations",violations,"httpRequests",server.getRequestCount(),"rate",2,"burst",1,"fixture429",provider429.get()));
        assertThat(events).hasSize(12);assertThat(server.getRequestCount()).isEqualTo(12);assertThat(violations).isZero();assertThat(provider429.get()).isZero();
      }finally{for(var p:children)if(p.isAlive())p.destroyForcibly();}
    }finally{gate.close();}
  }

  @RepeatedTest(3) void actualRedisRestartLosesStateAndRequiresConservativeExplicitRecovery()throws Exception {
    // This test owns this exact disposable service. It never selects a daily/production container.
    assertThat(System.getenv("W2_REDIS_CONTAINER")).isEqualTo("workhub-w2-redis");
    assertThat(port()).isEqualTo(26379);
    String scope="restart-"+UUID.randomUUID();var gate=gate(scope,1000,1);
    try{initialize(gate,scope,1000,1);gate.outcome(permit(gate),429,120000);
      var restart=new ProcessBuilder("docker","restart","workhub-w2-redis").redirectErrorStream(true).start();
      assertThat(restart.waitFor(15,TimeUnit.SECONDS)).isTrue();assertThat(restart.exitValue()).isZero();
      long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
      while(true){try{gate.acquire();fail("Restart must not create a fresh full bucket");}
        catch(dev.workhub.common.ApiException e){if(e.code.equals("MODEL_ADMISSION_MISSING"))break;assertThat(e.code).isEqualTo("MODEL_ADMISSION_UNAVAILABLE");assertThat(System.nanoTime()).isLessThan(until);Thread.sleep(100);}}
      var script=new DefaultRedisScript<List>();script.setLocation(new ClassPathResource("model/admission.lua"));script.setResultType(List.class);
      var result=redis(gate).execute(script,List.of(key(scope),key(scope)+":init"),"initialize","v1|1000.0|1","1000","1","120000",UUID.randomUUID().toString());
      assertThat(result.get(0).toString()).isEqualTo("INITIALIZED");assertThat(gate.acquire().reason()).isEqualTo("COOLDOWN");assertThat(redis(gate).opsForHash().get(key(scope),"tokens")).isEqualTo("0");
    }finally{gate.close();}
  }

  @RepeatedTest(3) void twoJvmsCompeteForOneActualHalfOpenProbe()throws Exception {
    String scope="probe-"+UUID.randomUUID();var gate=gate(scope,1000,10);
    Path dir=Path.of("target/model-admission",scope).toAbsolutePath();Files.createDirectories(dir);
    var release=new CountDownLatch(1);var children=new ArrayList<Process>();
    try(var server=new MockWebServer()){
      initialize(gate,scope,1000,10);for(int i=0;i<5;i++)gate.outcome(permit(gate),503,0);
      redis(gate).opsForHash().putAll(key(scope),Map.of("openUntil","0","at","0"));
      server.setDispatcher(new Dispatcher(){public MockResponse dispatch(RecordedRequest r)throws InterruptedException{release.await(15,TimeUnit.SECONDS);return new MockResponse().setBody("ok");}});server.start();
      for(int i=0;i<2;i++)children.add(new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/java").toString(),"-Xmx192m","-XX:ActiveProcessorCount=1","-cp",System.getProperty("java.class.path"),ModelAdmissionIT.class.getName(),scope,dir.toString(),""+i,server.url("/probe").toString(),"probe").redirectErrorStream(true).redirectOutput(dir.resolve("child-"+i+".log").toFile()).start());
      long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
      while(!(Files.exists(dir.resolve("ready-0"))&&Files.exists(dir.resolve("ready-1")))){assertThat(System.nanoTime()).isLessThan(until);Thread.sleep(20);}Files.createFile(dir.resolve("go"));
      while(!(Files.exists(dir.resolve("decision-0.json"))&&Files.exists(dir.resolve("decision-1.json")))){assertThat(System.nanoTime()).isLessThan(until);Thread.sleep(20);}
      var reasons=new ArrayList<String>();for(int i=0;i<2;i++)reasons.add(JSON.readTree(dir.resolve("decision-"+i+".json").toFile()).path("reason").asText());
      assertThat(reasons).containsExactlyInAnyOrder("PERMIT","PROBE_BUSY");release.countDown();
      for(var child:children){assertThat(child.waitFor(10,TimeUnit.SECONDS)).isTrue();assertThat(child.exitValue()).isZero();}
      assertThat(server.getRequestCount()).isEqualTo(1);assertThat(redis(gate).opsForHash().get(key(scope),"circuit")).isEqualTo("CLOSED");
      JSON.writeValue(dir.resolve("summary.json").toFile(),Map.of("reasons",reasons,"httpProbes",server.getRequestCount(),"openClockAdvancedByFixture",true));
    }finally{release.countDown();for(var child:children)if(child.isAlive())child.destroyForcibly();gate.close();}
  }
  public static void main(String[] args)throws Exception {
    String scope=args[0],index=args[2];Path dir=Path.of(args[1]);boolean probe=args.length>4;var gate=gate(scope,probe?1000:2,probe?10:1);
    try{try(var connection=redis(gate).getConnectionFactory().getConnection()){connection.ping();}Files.createFile(dir.resolve("ready-"+index));while(!Files.exists(dir.resolve("go")))Thread.sleep(10);
      var client=java.net.http.HttpClient.newHttpClient();var events=new ArrayList<Map<String,Object>>();
      if(probe){var p=gate.acquire();
        Path temporary=dir.resolve("decision-"+index+".tmp"),decision=dir.resolve("decision-"+index+".json");
        JSON.writeValue(temporary.toFile(),p);Files.move(temporary,decision);
        if(p.permitted()){assertThat(p.valid()).isTrue();var response=client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(args[3])).POST(java.net.http.HttpRequest.BodyPublishers.noBody()).build(),java.net.http.HttpResponse.BodyHandlers.discarding());gate.outcome(p,response.statusCode(),0);}
        return;
      }
      for(int i=0;i<6;i++){var p=permit(gate);assertThat(p.valid()).isTrue();long sent=System.currentTimeMillis();
        var r=client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(args[3])).POST(java.net.http.HttpRequest.BodyPublishers.noBody()).build(),java.net.http.HttpResponse.BodyHandlers.discarding());
        gate.outcome(p,r.statusCode(),0);events.add(Map.of("pid",ProcessHandle.current().pid(),"permitAt",p.at(),"sentAt",sent,"httpStatus",r.statusCode()));}
      JSON.writeValue(dir.resolve("events-"+index+".json").toFile(),events);JSON.writeValue(dir.resolve("permits-"+index+".json").toFile(),GRANTS);
    }finally{gate.close();}
  }
}
