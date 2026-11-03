package dev.workhub.agent;

import dev.workhub.common.*;
import dev.workhub.identity.ActorContext;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

/** Nonblocking servlet writes: no SseEmitter writer blocked behind a slow socket.
 * No replay queue: each send rereads one authoritative snapshot, coalescing intermediate stages.
 */
@Component
public class AgentProgressStreams {
  static final int MAX_CONNECTIONS=64;
  static final long SLOW_NANOS=TimeUnit.SECONDS.toNanos(5);
  private final AgentProgress progress; private final Json json;
  private final Set<Connection> connections=ConcurrentHashMap.newKeySet();
  private final Semaphore slots=new Semaphore(MAX_CONNECTIONS);
  private final ScheduledThreadPoolExecutor polls=new ScheduledThreadPoolExecutor(2,daemon("agent-progress"));
  private final ScheduledExecutorService watchdog=Executors.newSingleThreadScheduledExecutor(daemon("agent-progress-expiry"));
  public AgentProgressStreams(AgentProgress progress,Json json) {
    this.progress=progress;this.json=json;polls.setRemoveOnCancelPolicy(true);
    watchdog.scheduleWithFixedDelay(()->connections.forEach(Connection::expire),100,100,TimeUnit.MILLISECONDS);
  }
  private static ThreadFactory daemon(String name) { return r->{var t=new Thread(r,name);t.setDaemon(true);return t;}; }
  public void open(ActorContext actor,UUID project,UUID run,HttpServletRequest request,HttpServletResponse response) throws IOException {
    var session=request.getSession(false);String sessionId=session==null?null:session.getId();
    progress.streamSnapshot(actor,project,run,sessionId); // Reject before committing response headers.
    if(!slots.tryAcquire())throw new ApiException(503,"PROGRESS_CAPACITY","进度订阅已满，请稍后重新连接");
    Connection c=null;
    try {
      response.setContentType("text/event-stream");response.setCharacterEncoding("UTF-8");
      response.setHeader("Cache-Control","no-store");response.setHeader("X-Accel-Buffering","no");
      var async=request.startAsync();async.setTimeout(65000);
      c=new Connection(actor,project,run,sessionId,async,response.getOutputStream());connections.add(c);
      final var connection=c;
      async.addListener(new AsyncListener() {
        public void onComplete(AsyncEvent e){connection.close();}
        public void onTimeout(AsyncEvent e){connection.close();}
        public void onError(AsyncEvent e){connection.close();}
        public void onStartAsync(AsyncEvent e){}
      });
      c.output.setWriteListener(new WriteListener() {
        public void onWritePossible(){} // The bounded polling executor performs all writes.
        public void onError(Throwable e){connection.close();}
      });
      c.future=polls.scheduleWithFixedDelay(c::pump,0,500,TimeUnit.MILLISECONDS);
      if(c.closed.get())c.future.cancel(false);
    } catch(RuntimeException|IOException e) {if(c!=null)c.close();else slots.release();throw e;}
  }
  final class Connection {
    final ActorContext actor;final UUID project,run;final String sessionId;final AsyncContext async;final ServletOutputStream output;
    final AtomicBoolean closed=new AtomicBoolean();final long began=System.nanoTime();
    volatile long stalledAt;volatile ScheduledFuture<?> future;
    Connection(ActorContext actor,UUID project,UUID run,String sessionId,AsyncContext async,ServletOutputStream output) {
      this.actor=actor;this.project=project;this.run=run;this.sessionId=sessionId;this.async=async;this.output=output;
    }
    void pump() {
      if(closed.get())return;
      try {
        if(!output.isReady()){if(stalledAt==0)stalledAt=System.nanoTime();return;}
        // Fresh current membership and run visibility on EVERY content send. Transaction ends here.
        var value=progress.streamSnapshot(actor,project,run,sessionId);
        if(closed.get())return;
        byte[] frame=("retry: 1000\nid: "+value.sequence()+":"+value.status()+"\nevent: snapshot\ndata: "+json.write(value)+"\n\n").getBytes(StandardCharsets.UTF_8);
        if(frame.length>4096)throw new IllegalStateException("Progress frame exceeded fixed bound");
        stalledAt=System.nanoTime();output.write(frame);output.flush();stalledAt=0;
        if(value.terminal())close();
      }catch(Exception e){close();}
    }
    void expire() {
      long now=System.nanoTime();
      if(now-began>=TimeUnit.SECONDS.toNanos(60)||(stalledAt!=0&&now-stalledAt>=SLOW_NANOS))close();
    }
    void close() {
      if(!closed.compareAndSet(false,true))return;
      if(future!=null)future.cancel(false);connections.remove(this);slots.release();
      try{async.complete();}catch(IllegalStateException ignored){}
    }
  }
  @PreDestroy void shutdown(){connections.forEach(Connection::close);polls.shutdownNow();watchdog.shutdownNow();}
}
