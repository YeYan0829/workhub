package dev.workhub.phase5a;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;

/** Derived search artifacts belong to one disposable fixture, never the business/demo index. */
final class Phase5aIndexCleanup {
  static void delete(String index)throws Exception {
    if(!index.matches("p5a-(it|trial)-[0-9a-f-]{36}(-[0-9a-f-]{36})?"))throw new IllegalArgumentException("Not an isolated fixture index");
    var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+System.getenv().getOrDefault("ELASTICSEARCH_PORT","19200")+"/"+index))
        .timeout(Duration.ofSeconds(10)).DELETE().build();
    var response=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build().send(request,HttpResponse.BodyHandlers.discarding());
    if(response.statusCode()!=200&&response.statusCode()!=404)throw new IllegalStateException("Fixture index cleanup failed: "+response.statusCode());
  }
}
