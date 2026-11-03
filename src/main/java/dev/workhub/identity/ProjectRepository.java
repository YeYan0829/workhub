package dev.workhub.identity;

import static dev.workhub.common.Ids.*;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.*;

@Repository
public class ProjectRepository {
    private final JdbcTemplate jdbc;
    private final RowMapper<Project> mapper =
            (r, n) ->
                    new Project(
                            read(r, "id"),
                            read(r, "workspace_id"),
                            r.getString("name"),
                            r.getLong("business_revision"),
                            dev.workhub.common.Utc.read(r, "created_at"),
                            r.getString("description"),
                            r.getBoolean("archived"));

    public ProjectRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Project> find(UUID id) {
        return find(id, false);
    }

    public Optional<Project> lock(UUID id) {
        return find(id, true);
    }

    private Optional<Project> find(UUID id, boolean lock) {
        return jdbc
                .query(
                        "SELECT * FROM project WHERE id=?" + (lock ? " FOR UPDATE" : ""),
                        mapper,
                        bytes(id))
                .stream()
                .findFirst();
    }

    public void insert(UUID id, UUID workspace, String name) {
        jdbc.update(
                "INSERT INTO project(id,workspace_id,name) VALUES (?,?,?)",
                bytes(id),
                bytes(workspace),
                name);
    }

    public void increment(UUID project) {
        jdbc.update(
                "UPDATE project SET business_revision=business_revision+1 WHERE id=?",
                bytes(project));
    }

    public List<Project> list(UUID workspace, UUID user) {
        return jdbc.query(
                "SELECT p.* FROM project p JOIN project_member m ON m.project_id=p.id WHERE"
                        + " p.workspace_id=? AND m.user_id=? AND m.active ORDER BY p.id",
                mapper,
                bytes(workspace),
                bytes(user));
    }
}
