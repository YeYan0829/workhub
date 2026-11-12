package dev.workhub.work;

import static dev.workhub.common.Ids.*;

import dev.workhub.common.*;
import dev.workhub.identity.*;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.util.*;

/** Short database reads; no transaction is held for a streaming connection. */
@Service
public class GraphStreamReads {
    private final JdbcTemplate db;
    private final ProjectAccess access;
    private final OverviewState states;

    public GraphStreamReads(JdbcTemplate db, ProjectAccess access, OverviewState states) {
        this.db = db;
        this.access = access;
        this.states = states;
    }

    record Identity(UUID project, UUID user, String session) {}

    record Version(UUID project, long seq, long business, long topology, long retained) {}

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 2)
    public OverviewState.State register(ActorContext actor, UUID project, String session) {
        access.read(actor, project);
        if (session == null
                || !authorized(Set.of(new Identity(project, actor.userId(), session)))
                        .contains(new Identity(project, actor.userId(), session)))
            throw ApiException.forbidden();
        return states.read(project);
    }

    public Set<Identity> authorized(Set<Identity> identities) {
        if (identities.isEmpty()) return Set.of();
        var args = new ArrayList<Object>();
        var selects = new ArrayList<String>();
        for (var key : identities) {
            selects.add("SELECT ? project_id,? user_id,? session_id");
            args.add(bytes(key.project()));
            args.add(bytes(key.user()));
            args.add(key.session());
        }
        args.add(System.currentTimeMillis());
        String sql =
                "SELECT r.project_id,r.user_id,r.session_id FROM ("
                        + String.join(" UNION ALL ", selects)
                        + ") r JOIN project p ON p.id=r.project_id JOIN app_user u ON"
                        + " u.id=r.user_id AND u.active JOIN workspace_member wm ON"
                        + " wm.workspace_id=p.workspace_id AND wm.user_id=u.id AND wm.active JOIN"
                        + " project_member pm ON pm.project_id=p.id AND pm.user_id=u.id AND"
                        + " pm.active JOIN SPRING_SESSION s ON s.SESSION_ID=r.session_id AND"
                        + " CAST(s.PRINCIPAL_NAME AS BINARY)=CAST(u.login AS BINARY) AND"
                        + " s.EXPIRY_TIME>?";
        return new HashSet<>(
                db.query(
                        sql,
                        (r, n) ->
                                new Identity(
                                        read(r, "project_id"),
                                        read(r, "user_id"),
                                        r.getString("session_id")),
                        args.toArray()));
    }

    public Map<UUID, Version> versions(Set<UUID> projects) {
        if (projects.isEmpty()) return Map.of();
        var args = new ArrayList<Object>();
        projects.forEach(id -> args.add(bytes(id)));
        var result = new HashMap<UUID, Version>();
        db.query(
                "SELECT"
                    + " s.project_id,s.view_seq,s.topology_revision,s.retained_after_seq,p.business_revision"
                    + " FROM project_overview_state s JOIN project p ON p.id=s.project_id WHERE"
                    + " s.project_id IN ("
                        + GraphReadService.marks(projects.size())
                        + ")",
                r -> {
                    UUID p = read(r, "project_id");
                    result.put(
                            p,
                            new Version(
                                    p,
                                    r.getLong("view_seq"),
                                    r.getLong("business_revision"),
                                    r.getLong("topology_revision"),
                                    r.getLong("retained_after_seq")));
                },
                args.toArray());
        return result;
    }
}
