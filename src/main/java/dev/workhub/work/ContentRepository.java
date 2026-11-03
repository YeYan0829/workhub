package dev.workhub.work;

import static dev.workhub.common.Ids.*;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.*;

@Repository
public class ContentRepository {
    private final JdbcTemplate jdbc;

    public ContentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public enum DocumentKind {
        PLAN,
        MEETING_NOTES,
        REPORT,
        DOCUMENT
    }

    public record Document(
            UUID id, String kind, String title, String body, long revision, Instant updatedAt) {}

    public record Comment(
            UUID id, UUID taskId, UUID authorId, String body, long revision, Instant createdAt) {}

    public record Resource(UUID id, String url) {}

    public void insertDocument(
            UUID project, UUID id, DocumentKind kind, String title, String body, UUID creator) {
        jdbc.update(
                "INSERT INTO project_document(id,project_id,kind,title,body,created_by) VALUES"
                        + " (?,?,?,?,?,?)",
                bytes(id),
                bytes(project),
                kind.name(),
                title,
                body,
                bytes(creator));
    }

    public Optional<Document> document(UUID project, UUID id) {
        return jdbc
                .query(
                        "SELECT * FROM project_document WHERE project_id=? AND id=? AND deleted=FALSE",
                        (r, n) ->
                                new Document(
                                        read(r, "id"),
                                        r.getString("kind"),
                                        r.getString("title"),
                                        r.getString("body"),
                                        r.getLong("revision"),
                                        dev.workhub.common.Utc.read(r, "updated_at")),
                        bytes(project),
                        bytes(id))
                .stream()
                .findFirst();
    }

    public List<Document> documents(UUID project, TaskRepository.Cursor cursor, int limit) {
        return documents(project, cursor, limit, "");
    }

    public List<Document> documents(UUID project, TaskRepository.Cursor cursor, int limit, String query) {
        return documentCandidates(project, cursor, limit, query.isBlank() ? List.of() : List.of(query));
    }

    public List<Document> documentCandidates(UUID project, TaskRepository.Cursor cursor, int limit, List<String> terms) {
        String condition = cursor == null ? "" : " AND (updated_at<? OR (updated_at=? AND id<?))";
        List<Object> args = new ArrayList<>();
        args.add(bytes(project));
        if (!terms.isEmpty()) {
            condition += " AND (" + String.join(" OR ", java.util.Collections.nCopies(terms.size(), "(LOCATE(?,title)>0 OR LOCATE(?,body)>0)")) + ")";
        }
        if (cursor != null) {
            args.add(dev.workhub.common.Utc.value(cursor.updatedAt()));
            args.add(dev.workhub.common.Utc.value(cursor.updatedAt()));
            args.add(bytes(cursor.id()));
        }
        for (String term : terms) { args.add(term); args.add(term); }
        args.add(limit);
        return jdbc.query(
                "SELECT * FROM project_document WHERE project_id=? AND deleted=FALSE"
                        + condition
                        + " ORDER BY updated_at DESC,id DESC LIMIT ?",
                (r, n) ->
                        new Document(
                                read(r, "id"),
                                r.getString("kind"),
                                r.getString("title"),
                                r.getString("body"),
                                r.getLong("revision"),
                                dev.workhub.common.Utc.read(r, "updated_at")),
                args.toArray());
    }

    public void deleteComment(UUID project, UUID id) {
        jdbc.update(
                "UPDATE comment SET deleted=TRUE,body='[deleted]',revision=revision+1 WHERE"
                        + " project_id=? AND id=?",
                bytes(project),
                bytes(id));
    }

    public void deleteDocument(UUID project, UUID id) {
        jdbc.update("UPDATE project_document SET deleted=TRUE,title='[deleted]',body='',revision=revision+1 WHERE project_id=? AND id=?",bytes(project),bytes(id));
    }

    public int updateDocument(UUID project, UUID id, String title, String body, long expected) {
        return jdbc.update(
                "UPDATE project_document SET"
                    + " title=?,body=?,revision=revision+1,updated_at=CURRENT_TIMESTAMP(6) WHERE"
                    + " project_id=? AND id=? AND revision=?",
                title,
                body,
                bytes(project),
                bytes(id),
                expected);
    }

    public void insertComment(UUID project, UUID task, UUID id, UUID author, String body) {
        jdbc.update(
                "INSERT INTO comment(id,project_id,task_id,author_id,body) VALUES (?,?,?,?,?)",
                bytes(id),
                bytes(project),
                bytes(task),
                bytes(author),
                body);
    }

    public Optional<Comment> comment(UUID project, UUID task, UUID id) {
        return jdbc
                .query(
                        "SELECT * FROM comment WHERE deleted=FALSE AND project_id=? AND task_id=?"
                                + " AND id=?",
                        (r, n) ->
                                new Comment(
                                        read(r, "id"),
                                        read(r, "task_id"),
                                        read(r, "author_id"),
                                        r.getString("body"),
                                        r.getLong("revision"),
                                        dev.workhub.common.Utc.read(r, "created_at")),
                        bytes(project),
                        bytes(task),
                        bytes(id))
                .stream()
                .findFirst();
    }

    public Optional<UUID> commentTask(UUID project, UUID id) {
        return jdbc.query("SELECT task_id FROM comment WHERE project_id=? AND id=? AND deleted=FALSE",
            (r,n)->read(r,"task_id"),bytes(project),bytes(id)).stream().findFirst();
    }

    public List<Comment> comments(
            UUID project, UUID task, TaskRepository.Cursor cursor, int limit) {
        String condition = cursor == null ? "" : " AND (created_at>? OR (created_at=? AND id>?))";
        List<Object> args = new ArrayList<>();
        args.add(bytes(project));
        args.add(bytes(task));
        if (cursor != null) {
            args.add(dev.workhub.common.Utc.value(cursor.updatedAt()));
            args.add(dev.workhub.common.Utc.value(cursor.updatedAt()));
            args.add(bytes(cursor.id()));
        }
        args.add(limit);
        return jdbc.query(
                "SELECT * FROM comment WHERE deleted=FALSE AND project_id=? AND task_id=?"
                        + condition
                        + " ORDER BY created_at,id LIMIT ?",
                (r, n) ->
                        new Comment(
                                read(r, "id"),
                                read(r, "task_id"),
                                read(r, "author_id"),
                                r.getString("body"),
                                r.getLong("revision"),
                                dev.workhub.common.Utc.read(r, "created_at")),
                args.toArray());
    }

    public int updateComment(UUID project, UUID task, UUID id, String body, long expected) {
        return jdbc.update(
                "UPDATE comment SET body=?,revision=revision+1 WHERE project_id=? AND task_id=? AND"
                        + " id=? AND revision=?",
                body,
                bytes(project),
                bytes(task),
                bytes(id),
                expected);
    }

    public Optional<UUID> resourceByKey(UUID project, String key) {
        return jdbc
                .query(
                        "SELECT id FROM external_resource WHERE project_id=? AND kind='URL' AND"
                                + " provider_key=?",
                        (r, n) -> uuid(r.getBytes(1)),
                        bytes(project),
                        key)
                .stream()
                .findFirst();
    }

    public void insertResource(UUID project, UUID id, String key, String url) {
        jdbc.update(
                "INSERT INTO external_resource(id,project_id,kind,provider_key,url) VALUES"
                        + " (?,?,'URL',?,?)",
                bytes(id),
                bytes(project),
                key,
                url);
    }

    public boolean linked(UUID project, UUID task, UUID target, boolean document) {
        String table = document ? "task_document" : "task_resource",
                column = document ? "document_id" : "resource_id";
        return jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM "
                        + table
                        + " WHERE project_id=? AND task_id=? AND "
                        + column
                        + "=?)",
                Boolean.class,
                bytes(project),
                bytes(task),
                bytes(target));
    }

    public void link(UUID project, UUID task, UUID target, boolean document, boolean add) {
        String table = document ? "task_document" : "task_resource",
                column = document ? "document_id" : "resource_id";
        if (add)
            jdbc.update(
                    "INSERT INTO " + table + "(project_id,task_id," + column + ") VALUES (?,?,?)",
                    bytes(project),
                    bytes(task),
                    bytes(target));
        else
            jdbc.update(
                    "DELETE FROM "
                            + table
                            + " WHERE project_id=? AND task_id=? AND "
                            + column
                            + "=?",
                    bytes(project),
                    bytes(task),
                    bytes(target));
    }

    public List<UUID> linkedDocuments(UUID project, UUID task) {
        return jdbc.query(
                "SELECT t.document_id FROM task_document t JOIN project_document d ON"
                    + " d.project_id=t.project_id AND d.id=t.document_id WHERE t.project_id=?"
                    + " AND t.task_id=? AND NOT d.deleted",
                (r, n) -> uuid(r.getBytes(1)),
                bytes(project),
                bytes(task));
    }

    public List<Resource> linkedResources(UUID project, UUID task) {
        return jdbc.query(
                "SELECT r.id,r.url FROM external_resource r JOIN task_resource t ON"
                    + " t.project_id=r.project_id AND t.resource_id=r.id WHERE t.project_id=? AND"
                    + " t.task_id=?",
                (r, n) -> new Resource(read(r, "id"), r.getString("url")),
                bytes(project),
                bytes(task));
    }
}
