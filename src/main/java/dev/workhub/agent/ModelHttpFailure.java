package dev.workhub.agent;

import dev.workhub.common.ApiException;
import java.time.*;
import java.time.format.DateTimeFormatter;

/** Only allowlisted transport facts; no response body, account headers or credentials. */
public class ModelHttpFailure extends ApiException {
  public final int httpStatus;
  public final long cooldownMillis;
  public ModelHttpFailure(int status,String retryAfter,Instant receivedAt){
    super(503,status==429?"GLM_RATE_LIMIT":"GLM_HTTP_"+status,"模型服务暂不可用");
    this.httpStatus=status;this.cooldownMillis=status==429?retryAfter(retryAfter,receivedAt):0;
  }
  public boolean retryable(){return (httpStatus==429 && cooldownMillis>=0) || httpStatus==502 || httpStatus==503 || httpStatus==504;}
  static long retryAfter(String value,Instant now){
    if(value==null)return 30000;
    try{
      String s=value.trim();long delay=s.matches("[0-9]+")?Math.multiplyExact(Long.parseLong(s),1000):Math.max(0,Duration.between(now,ZonedDateTime.parse(s,DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).toMillis());
      // Lua numbers represent these integers exactly; impossible dates do not wrap or truncate.
      if(delay>8_000_000_000_000_000L)return -1;
      return delay;
    }catch(RuntimeException e){return -1;}
  }
}
