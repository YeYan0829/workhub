package dev.workhub.governance;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Local-only fault proxy: forward a valid acquisition, discard its response without closing TCP. */
final class RedisResponseLossProxy implements AutoCloseable {
  private final ServerSocket listener;
  private final ExecutorService executor=Executors.newCachedThreadPool();
  private volatile Socket client,upstream;
  final AtomicBoolean dropped=new AtomicBoolean();
  RedisResponseLossProxy(int upstreamPort)throws IOException {
    listener=new ServerSocket(0,1,InetAddress.getLoopbackAddress());
    executor.submit(()->{
      try{client=listener.accept();upstream=new Socket(InetAddress.getLoopbackAddress(),upstreamPort);
        executor.submit(()->relay(upstream,client,false));relay(client,upstream,true);
      }catch(IOException ignored){}
    });
  }
  int port(){return listener.getLocalPort();}
  private void relay(Socket from,Socket to,boolean request){
    try{byte[] buffer=new byte[65536];String tail="";
      for(int n;(n=from.getInputStream().read(buffer))>=0;){
        if(request){String text=tail+new String(buffer,0,n,StandardCharsets.US_ASCII);if(text.contains("$7\r\nacquire\r\n"))dropped.set(true);tail=text.substring(Math.max(0,text.length()-24));}
        if(request || !dropped.get()){to.getOutputStream().write(buffer,0,n);to.getOutputStream().flush();}
      }
    }catch(IOException ignored){}
  }
  public void close()throws IOException {listener.close();if(client!=null)client.close();if(upstream!=null)upstream.close();executor.shutdownNow();}
}
