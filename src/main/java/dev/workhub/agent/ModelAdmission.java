package dev.workhub.agent;

import dev.workhub.common.*;
import java.time.Duration;
import java.util.*;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/** Short Redis operations only. No sleeps, database access, or implicit initialization. */
@Component
public class ModelAdmission {
  public record Decision(String reason,long at,long delay,String ticket,long validUntilNanos) {
    public boolean permitted(){return reason.equals("PERMIT");}
    public boolean valid(){return permitted() && System.nanoTime()<validUntilNanos;}
  }
  private final boolean enabled;
  private final String key, contract;
  private final StringRedisTemplate redis;
  private final LettuceConnectionFactory connection;
  private final DefaultRedisScript<List> script=new DefaultRedisScript<>();
  public ModelAdmission(
      @Value("${workhub.model-admission.enabled:false}") boolean enabled,
      @Value("${workhub.model-admission.scope:}") String scope,
      @Value("${workhub.model-admission.requests-per-second:2}") double rate,
      @Value("${workhub.model-admission.burst:1}") int burst,
      @Value("${workhub.model-admission.redis-host:127.0.0.1}") String host,
      @Value("${workhub.model-admission.redis-port:16380}") int port,
      @Value("${workhub.model-admission.redis-password:}") String password) {
    if(enabled && (!scope.matches("[A-Za-z0-9_.-]{1,100}") || !Double.isFinite(rate) || rate<=0 || rate>10000 || burst<1 || burst>10000))throw new IllegalArgumentException("Invalid model quota contract");
    this.enabled=enabled;this.key="workhub:model:{"+scope+"}";this.contract="v1|"+Double.toString(rate)+"|"+burst;
    script.setLocation(new ClassPathResource("model/admission.lua"));script.setResultType(List.class);
    if(enabled){
      var config=new RedisStandaloneConfiguration(host,port);if(!password.isBlank())config.setPassword(password);
      var socket=io.lettuce.core.SocketOptions.builder().connectTimeout(Duration.ofMillis(500)).build();
      var options=io.lettuce.core.ClientOptions.builder().socketOptions(socket).autoReconnect(true)
          .disconnectedBehavior(io.lettuce.core.ClientOptions.DisconnectedBehavior.REJECT_COMMANDS).build();
      connection=new LettuceConnectionFactory(config,LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(500)).clientOptions(options).shutdownTimeout(Duration.ZERO).build());
      connection.afterPropertiesSet();redis=new StringRedisTemplate(connection);
      // Initialize the driver before workers start. A first user's 500 ms command
      // must not also pay for cold Netty/client or Lua codec class loading.
      // Redis being unavailable must still leave ordinary project APIs usable.
      long warmStart=System.nanoTime();
      try(var probe=connection.getConnection()) {
        probe.ping();
        // An unrecognized operation only reads state and returns INVALID/MISSING/
        // CONFIG. It cannot initialize a bucket, grant quota or change cooldown.
        execute("warmup","warmup","0","0");
        org.slf4j.LoggerFactory.getLogger(ModelAdmission.class).info("Model admission command pipeline initialized in {} ms",(System.nanoTime()-warmStart)/1_000_000);
      }catch(RuntimeException e) {
        org.slf4j.LoggerFactory.getLogger(ModelAdmission.class).warn("Model admission connection unavailable at startup; model requests remain fail-closed ({})",e.getClass().getSimpleName());
      }
    }else{connection=null;redis=null;}
  }
  public boolean enabled(){return enabled;}
  public Decision acquire(){
    long began=System.nanoTime();String ticket=UUID.randomUUID().toString();
    if(!enabled)return new Decision("PERMIT",System.currentTimeMillis(),0,ticket,Long.MAX_VALUE);
    var result=execute("acquire",ticket,"0","0");
    String reason=result.get(0).toString();long at=number(result.get(1)),delay=number(result.get(2));
    if(Set.of("MISSING","CONFIG","RECOVERY_REQUIRED").contains(reason))throw new ApiException(503,"MODEL_ADMISSION_"+reason,"共享模型准入需要运维检查或恢复");
    return new Decision(reason,at,delay,ticket,began+500_000_000L);
  }
  public void outcome(Decision permit,int status,long cooldownMillis){
    if(enabled)execute("outcome",permit.ticket(),""+status,""+cooldownMillis);
  }
  private List execute(String operation,String ticket,String status,String delay){
    try{return Objects.requireNonNull(redis.execute(script,List.of(key,key+":permit:"+ticket),operation,contract,status,delay));}
    catch(RuntimeException e){
      Throwable cause=e;for(int i=0;i<5 && cause.getCause()!=null;i++)cause=cause.getCause();
      // Safe type only: connection URLs, credentials and raw Redis messages stay private.
      org.slf4j.LoggerFactory.getLogger(ModelAdmission.class).warn("Model admission command failed ({})",cause.getClass().getSimpleName());
      throw new ApiException(503,"MODEL_ADMISSION_UNAVAILABLE","共享模型准入服务不可用，未获确认的请求不会发出");
    }
  }
  private static long number(Object value){return value instanceof Number n?n.longValue():Long.parseLong(value.toString());}
  @PreDestroy public void close(){if(connection!=null)connection.destroy();}
}
