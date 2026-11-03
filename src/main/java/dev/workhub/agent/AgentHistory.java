package dev.workhub.agent;

import dev.workhub.common.ApiException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Descending keyset pagination; equal timestamps are ordered by the binary UUID column. */
public final class AgentHistory {
  private AgentHistory() {}
  public record Page<T>(List<T> items,String nextCursor) {}
  record Cursor(Instant createdAt,UUID id) {
    String encode(){return createdAt+"~"+id;}
  }
  static Cursor parse(String raw) {
    if(raw==null)return null;
    try {
      if(raw.length()>100)throw new IllegalArgumentException();
      var parts=raw.split("~",-1);
      if(parts.length!=2)throw new IllegalArgumentException();
      var time=Instant.parse(parts[0]);var id=UUID.fromString(parts[1]);
      if(!id.toString().equals(parts[1]) || time.getNano()%1000!=0
          || time.isBefore(Instant.parse("1000-01-01T00:00:00Z"))
          || !time.isBefore(Instant.parse("9999-12-31T23:59:59Z")))throw new IllegalArgumentException();
      return new Cursor(time,id);
    }catch(RuntimeException e){throw ApiException.invalid("历史分页游标无效");}
  }
  static int limit(int value){if(value<1||value>50)throw ApiException.invalid("历史分页大小须为1至50");return value;}
}
