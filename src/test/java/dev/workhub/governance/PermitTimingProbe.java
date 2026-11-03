package dev.workhub.governance;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workhub.agent.*;
import java.lang.management.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import javax.sql.DataSource;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/** Opt-in test-side observation of the real pipeline. Never emits SQL values or credentials. */
public final class PermitTimingProbe {
  private final List<Map<String,Object>> events=Collections.synchronizedList(new ArrayList<>());
  private final ThreadMXBean threads=ManagementFactory.getThreadMXBean();
  public PermitTimingProbe(){if(threads.isThreadContentionMonitoringSupported())threads.setThreadContentionMonitoringEnabled(true);}
  private long[] stamp(){long id=Thread.currentThread().getId();var info=threads.getThreadInfo(id);return new long[]{System.nanoTime(),threads.getCurrentThreadCpuTime(),info.getBlockedTime(),info.getWaitedTime()};}
  private void record(String stage,long[] start,Map<String,Object> extra){
    var end=stamp();var e=new LinkedHashMap<String,Object>();e.put("stage",stage);e.put("thread",Thread.currentThread().getId());e.put("startNs",start[0]);e.put("endNs",end[0]);e.put("wallMs",(end[0]-start[0])/1e6);e.put("cpuMs",(end[1]-start[1])/1e6);e.put("blockedMs",end[2]-start[2]);e.put("waitedMs",end[3]-start[3]);e.putAll(extra);events.add(e);
  }
  public AgentAudit audit(AgentAudit delegate){var p=new ProxyFactory(delegate);p.setProxyTargetClass(true);p.addAdvice((MethodInterceptor)call->{long[] start=stamp();try{return call.proceed();}finally{record("audit."+call.getMethod().getName(),start,Map.of());}});return (AgentAudit)p.getProxy();}
  public DataSource source(DataSource delegate){return new DelegatingDataSource(delegate){@Override public Connection getConnection()throws SQLException{
    long[] start=stamp();Connection c;try{c=super.getConnection();}finally{record("pool.borrow",start,Map.of());}
    return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class[]{Connection.class},(p,m,a)->{
      long[] at=stamp();Object value;
      try{value=m.invoke(c,a);}catch(InvocationTargetException e){throw e.getCause();}finally{if(Set.of("commit","rollback").contains(m.getName()))record("jdbc."+m.getName(),at,Map.of());}
      if(m.getName().equals("prepareStatement")&&value instanceof PreparedStatement ps){String sql=(String)a[0];String verb=sql.split(" ")[0];return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),new Class[]{PreparedStatement.class},(sp,sm,sa)->{long[] t=stamp();try{return sm.invoke(ps,sa);}catch(InvocationTargetException e){throw e.getCause();}finally{if(sm.getName().startsWith("execute"))record("jdbc."+verb,t,Map.of("sqlHash",Integer.toHexString(sql.hashCode())));}});}
      return value;
    });
  }};}
  public ModelAdmission gate(ModelAdmission delegate){return new ModelAdmission(false,"",1,1,"127.0.0.1",1,""){
    @Override public boolean enabled(){return delegate.enabled();}
    @Override public Decision acquire(){long[] start=stamp();Decision d=delegate.acquire();record("gate."+d.reason(),start,Map.of("validUntilNs",d.validUntilNanos(),"ticket",d.ticket()));return d;}
    @Override public void outcome(Decision d,int status,long cooldown){delegate.outcome(d,status,cooldown);}
    @Override public void close(){delegate.close();}
  };}
  public GlmChatModel model(AgentSettings settings){return new GlmChatModel(settings){
    @Override public Reply call(java.util.List<org.springframework.ai.chat.messages.Message> messages,java.util.List<org.springframework.ai.openai.api.OpenAiApi.FunctionTool> tools,long remaining,Runnable before){
      long[] start=stamp();return super.call(messages,tools,remaining,()->{
        record("client.setup",start,Map.of("identity",messages.get(messages.size()-1).getText()));
        long[] check=stamp();try{before.run();}finally{record("send.callback",check,Map.of());}
      });
    }
  };}
  public void write(Path path)throws Exception{new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(path.toFile(),events);}
}
