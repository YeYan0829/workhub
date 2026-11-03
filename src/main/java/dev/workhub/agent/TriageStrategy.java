package dev.workhub.agent;

/** Investigation policy only. All modes have the same read-only authorization boundary. */
public enum TriageStrategy {
  WORKFLOW, GUIDED, AGENT
}
