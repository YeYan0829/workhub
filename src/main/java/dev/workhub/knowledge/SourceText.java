package dev.workhub.knowledge;

import static dev.workhub.common.Ids.*;

import dev.workhub.common.Inputs;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SourceText {
  private final JdbcTemplate db;

  public SourceText(JdbcTemplate db) {
    this.db = db;
  }

  public record Owner(
      UUID id, String kind, String title, String body, boolean visible, String url) {
    public String text() {
      return title.isEmpty() ? body : title + "\n" + body;
    }
  }

  public record Source(
      UUID id, UUID project, long revision, String hash, boolean active, Owner owner) {
    public boolean eligible() {
      return active && owner.visible() && hash.equals(Inputs.hash(owner.text()));
    }
  }

  public Owner owner(UUID project, String kind, UUID id) {
    String sql =
        switch (kind) {
          case "TASK" ->
              "SELECT title,description body,TRUE visible FROM task WHERE project_id=? AND id=?";
          case "DOCUMENT" ->
              "SELECT title,body,NOT deleted visible FROM project_document WHERE project_id=? AND"
                  + " id=?";
          case "COMMENT" ->
              "SELECT '' title,body,NOT deleted visible,task_id FROM comment WHERE project_id=? AND"
                  + " id=?";
          case "GITHUB_COMMENT" ->
              "SELECT '' title,body,active visible,resource_id,remote_id FROM github_comment WHERE"
                  + " project_id=? AND id=?";
          default -> throw new IllegalArgumentException("Unknown source owner");
        };
    var rows = db.queryForList(sql, bytes(project), bytes(id));
    if (rows.isEmpty()) return new Owner(id, kind, "", "", false, "");
    var r = rows.get(0);
    String url = "/projects/" + project;
    if (kind.equals("TASK")) url += "/tasks/" + id;
    else if (kind.equals("DOCUMENT")) url += "/documents/" + id;
    else if (kind.equals("COMMENT"))
      url += "/tasks/" + uuid((byte[]) r.get("task_id")) + "/comments/" + id;
    else {
      var links =
          db.queryForList(
              "SELECT t.task_id FROM github_issue t WHERE resource_id=?", r.get("resource_id"));
      if (!links.isEmpty() && links.get(0).get("task_id") != null)
        url += "/tasks/" + uuid((byte[]) links.get(0).get("task_id"));
    }
    return new Owner(
        id, kind, (String) r.get("title"), (String) r.get("body"), truth(r.get("visible")), url);
  }

  public Source load(UUID project, UUID source) {
    var rows =
        db.queryForList(
            "SELECT * FROM knowledge_source WHERE project_id=? AND id=?",
            bytes(project),
            bytes(source));
    if (rows.isEmpty()) throw new IllegalArgumentException("Source outside project");
    var row = rows.get(0);
    for (String kind : List.of("TASK", "DOCUMENT", "COMMENT", "GITHUB_COMMENT")) {
      String column =
          kind.equals("TASK")
              ? "task_id"
              : kind.equals("DOCUMENT")
                  ? "document_id"
                  : kind.equals("COMMENT") ? "comment_id" : "github_comment_id";
      if (row.get(column) != null) {
        var owner = owner(project, kind, uuid((byte[]) row.get(column)));
        // Provider URLs are metadata from the canonical source, never from ES or query input.
        owner =
            new Owner(
                owner.id(),
                owner.kind(),
                owner.title(),
                owner.body(),
                owner.visible(),
                (String) row.get("canonical_url"));
        return new Source(
            source,
            project,
            ((Number) row.get("source_revision")).longValue(),
            (String) row.get("body_hash"),
            truth(row.get("active")),
            owner);
      }
    }
    throw new IllegalStateException("Source must have one owner");
  }

  /** Request-local bounded owner reads; caller owns the repeatable-read snapshot. */
  public Map<UUID, Source> loadMany(UUID project, Collection<UUID> ids) {
    if (ids.isEmpty()) return Map.of();
    if (ids.size() > 32) throw new IllegalArgumentException("Source batch exceeds 32");
    var args = new ArrayList<Object>();
    args.add(bytes(project));
    ids.forEach(id -> args.add(bytes(id)));
    var sql =
        "SELECT s.*,COALESCE(t.title,d.title,'') owner_title,"
            + " COALESCE(t.description,d.body,c.body,x.body,'') owner_body, CASE WHEN s.task_id IS"
            + " NOT NULL THEN t.id IS NOT NULL WHEN s.document_id IS NOT NULL THEN d.id IS NOT NULL"
            + " AND NOT d.deleted WHEN s.comment_id IS NOT NULL THEN c.id IS NOT NULL AND NOT"
            + " c.deleted ELSE x.id IS NOT NULL AND x.active END owner_visible FROM"
            + " knowledge_source s LEFT JOIN task t ON t.id=s.task_id AND t.project_id=s.project_id"
            + " LEFT JOIN project_document d ON d.id=s.document_id AND d.project_id=s.project_id"
            + " LEFT JOIN comment c ON c.id=s.comment_id AND c.project_id=s.project_id LEFT JOIN"
            + " github_comment x ON x.id=s.github_comment_id AND x.project_id=s.project_id WHERE"
            + " s.project_id=? AND s.id IN ("
            + String.join(",", Collections.nCopies(ids.size(), "?"))
            + ")";
    Map<UUID, Source> result = new HashMap<>();
    for (var row : db.queryForList(sql, args.toArray())) {
      UUID id = uuid((byte[]) row.get("id"));
      String kind =
          row.get("task_id") != null
              ? "TASK"
              : row.get("document_id") != null
                  ? "DOCUMENT"
                  : row.get("comment_id") != null ? "COMMENT" : "GITHUB_COMMENT";
      String column =
          kind.equals("TASK")
              ? "task_id"
              : kind.equals("DOCUMENT")
                  ? "document_id"
                  : kind.equals("COMMENT") ? "comment_id" : "github_comment_id";
      var owner =
          new Owner(
              uuid((byte[]) row.get(column)),
              kind,
              (String) row.get("owner_title"),
              (String) row.get("owner_body"),
              truth(row.get("owner_visible")),
              (String) row.get("canonical_url"));
      result.put(
          id,
          new Source(
              id,
              project,
              ((Number) row.get("source_revision")).longValue(),
              (String) row.get("body_hash"),
              truth(row.get("active")),
              owner));
    }
    return result;
  }

  private boolean truth(Object value) {
    return Boolean.TRUE.equals(value) || value instanceof Number n && n.intValue() != 0;
  }
}
