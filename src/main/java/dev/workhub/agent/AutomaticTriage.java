package dev.workhub.agent;

import static dev.workhub.common.Ids.*;
import dev.workhub.common.*;
import dev.workhub.identity.*;
import dev.workhub.work.*;
import java.util.*;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Project opt-in applies to new local submissions and eligible first GitHub imports. */
@Service
public class AutomaticTriage {
  private final JdbcTemplate db;
  private final ProjectAccess access;
  private final AgentSettings settings;
  private final AgentRuns runs;
  public AutomaticTriage(JdbcTemplate db,ProjectAccess access,AgentSettings settings,AgentRuns runs) {
    this.db=db;this.access=access;this.settings=settings;this.runs=runs;
  }
  public record Configuration(boolean enabled,TriageStrategy strategy,long revision) {}
  public record Update(boolean enabled,TriageStrategy strategy,long expectedRevision) {}
  public record Status(boolean enabled,String status,String errorCode) {}
  private Configuration configuration(UUID project) {
    return db.query("SELECT enabled,strategy,revision FROM project_triage_settings WHERE project_id=?",
        (r,n)->new Configuration(r.getBoolean(1),TriageStrategy.valueOf(r.getString(2)),r.getLong(3)),bytes(project))
        .stream().findFirst().orElse(new Configuration(false,TriageStrategy.WORKFLOW,0));
  }
  @Transactional(readOnly=true)
  public Configuration get(ActorContext actor,UUID project) {
    access.read(actor,project);access.maintainer(actor,project);return configuration(project);
  }
  @Transactional(isolation=Isolation.READ_COMMITTED)
  public Configuration update(ActorContext actor,UUID project,Update request) {
    access.lock(actor,project);access.maintainer(actor,project);
    var old=configuration(project);
    if(old.revision()!=request.expectedRevision())throw ApiException.conflict("TRIAGE_SETTINGS_REVISION_CONFLICT",old.revision());
    if(request.strategy()==null)throw ApiException.invalid("请选择自动分析方式");
    if(request.enabled())settings.available();
    db.update("INSERT INTO project_triage_settings(project_id,enabled,strategy,revision,updated_by,enabled_since) VALUES (?,?,?,?,?,IF(?,UTC_TIMESTAMP(6),NULL))"
        +" ON DUPLICATE KEY UPDATE enabled_since=IF(VALUES(enabled) AND NOT enabled,UTC_TIMESTAMP(6),enabled_since),enabled=VALUES(enabled),strategy=VALUES(strategy),revision=VALUES(revision),updated_by=VALUES(updated_by)",
        bytes(project),request.enabled(),request.strategy().name(),old.revision()+1,bytes(actor.userId()),request.enabled());
    if(!request.enabled()) {
      // Leave in-flight reservations/attempts intact so completion can settle them.
      db.update("UPDATE agent_run SET automatic_stop_requested=true WHERE project_id=? AND automatic_task_id IS NOT NULL AND status IN ('QUEUED','RUNNING')",bytes(project));
      db.update("UPDATE agent_run SET status='FAILED',error_code='AUTOMATIC_TRIAGE_DISABLED',completed_at=UTC_TIMESTAMP(6) WHERE project_id=? AND automatic_task_id IS NOT NULL AND status='QUEUED'",bytes(project));
    }
    return configuration(project);
  }
  @EventListener
  @Transactional(propagation=Propagation.MANDATORY)
  public void submitted(TaskService.Created event) {
    var task=event.task();
    if(task.origin()!=TaskOrigin.MANUAL || task.status()!=TaskStatus.TRIAGE || !task.triageRequired())return;
    // TaskService owns the project lock, shared with settings/archive/membership changes.
    var config=configuration(task.projectId());
    if(config.enabled())runs.createAutomatic(event.actor(),task,config.strategy());
  }
  @EventListener
  @Transactional(propagation=Propagation.MANDATORY)
  public void imported(dev.workhub.integration.github.GitHubIssueImported event) {
    var task=event.task();
    if(task.origin()!=TaskOrigin.GITHUB || task.status()!=TaskStatus.TRIAGE || !task.triageRequired()
        || !Set.of("LIVE","RECONCILE").contains(event.discoveryMode()) || event.sourceCreatedAt()==null)return;
    // GitHubHandler holds the project lock; registration and mirror commit together.
    var grants=db.query("SELECT strategy,updated_by,enabled_since FROM project_triage_settings WHERE project_id=? AND enabled=true",
        (r,n)->new Grant(TriageStrategy.valueOf(r.getString(1)),read(r,"updated_by"),Utc.read(r,"enabled_since")),bytes(task.projectId()));
    if(grants.isEmpty())return;
    var grant=grants.get(0);
    if(grant.since()==null || !event.sourceCreatedAt().isAfter(grant.since()) || event.sourceCreatedAt().isAfter(java.time.Instant.now()))return;
    runs.createImportedAutomatic(grant.actor(),task,grant.strategy(),event);
  }
  private record Grant(TriageStrategy strategy,UUID actor,java.time.Instant since) {}
  @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
  public Status status(ActorContext actor,UUID project,UUID task) {
    access.read(actor,project);
    if(!db.queryForObject("SELECT EXISTS(SELECT 1 FROM task WHERE project_id=? AND id=?)",Boolean.class,bytes(project),bytes(task)))throw ApiException.missing();
    var result=db.query("SELECT status,error_code FROM agent_run WHERE project_id=? AND triage_task_id=? ORDER BY created_at DESC,id DESC LIMIT 1",
        (r,n)->new Status(configuration(project).enabled(),r.getString(1),r.getString(2)),bytes(project),bytes(task));
    return result.stream().findFirst().orElse(new Status(configuration(project).enabled(),null,null));
  }
}
