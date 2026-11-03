package dev.workhub.phase5a;

import static dev.workhub.common.Ids.*;
import com.fasterxml.jackson.databind.JsonNode;
import dev.workhub.common.Utc;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Explicit business snapshot times; this is not a replay of historical operations. */
final class FixtureTimeline {
  static LocalDateTime utc(String value) {
    return Utc.value(LocalDateTime.parse(value,DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        .atZone(ZoneId.of("Asia/Shanghai")).toInstant());
  }
  static void apply(JdbcTemplate db,UUID project,JsonNode fixture,Map<String,UUID> ids) {
    db.update("UPDATE project SET created_at=? WHERE id=?",utc(fixture.path("projectCreatedAt").asText()),bytes(project));
    for(var task:fixture.path("tasks"))db.update("UPDATE task SET created_at=?,updated_at=? WHERE project_id=? AND id=?",
        utc(task.path("createdAt").asText()),utc(task.path("updatedAt").asText()),bytes(project),bytes(ids.get(task.path("ref").asText())));
    for(var doc:fixture.path("documents"))db.update("UPDATE project_document SET updated_at=? WHERE project_id=? AND id=?",
        utc(doc.path("updatedAt").asText()),bytes(project),bytes(ids.get(doc.path("ref").asText())));
    int invalid=db.queryForObject("SELECT COUNT(*) FROM task t JOIN project p ON p.id=t.project_id WHERE p.id=? AND (t.created_at<p.created_at OR t.updated_at<t.created_at)",Integer.class,bytes(project));
    invalid+=db.queryForObject("SELECT COUNT(*) FROM comment c JOIN task t ON t.id=c.task_id WHERE c.project_id=? AND c.created_at<t.created_at",Integer.class,bytes(project));
    invalid+=db.queryForObject("SELECT COUNT(*) FROM project_document d JOIN project p ON p.id=d.project_id WHERE d.project_id=? AND d.updated_at<p.created_at",Integer.class,bytes(project));
    if(invalid!=0)throw new AssertionError("Inconsistent fixture chronology: "+invalid);
  }
  static Object observations(JdbcTemplate db,UUID project) {
    return db.queryForList("SELECT t.title,t.created_at taskCreatedAt,t.updated_at taskUpdatedAt,MIN(c.created_at) firstCommentAt,MAX(c.created_at) lastCommentAt FROM task t LEFT JOIN comment c ON c.task_id=t.id WHERE t.project_id=? GROUP BY t.id,t.title,t.created_at,t.updated_at ORDER BY t.updated_at,t.id",bytes(project));
  }
}
