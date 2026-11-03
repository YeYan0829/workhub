package dev.workhub.agent;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Byte envelope is conservative, not a provider token counter. Output allowance stays in AgentSettings. */
@Component
public class ContextPolicy {
  @Value("${workhub.conversation.trigger-bytes:60000}") public int triggerBytes=60000;
  @Value("${workhub.conversation.recent-turns:2}") public int recentTurns=2;
  @Value("${workhub.conversation.summary-max-bytes:10000}") public int summaryMaxBytes=10000;
  @Value("${workhub.conversation.max-compactions:2}") public int maxCompactions=2;
  public int trigger(AgentSettings settings){return Math.min(Math.max(4000,triggerBytes),(int)(settings.maxRequestInputBound*.75));}
}
