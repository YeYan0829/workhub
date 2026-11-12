package dev.workhub.work;

import static dev.workhub.common.Ids.*;

import dev.workhub.common.ApiException;
import dev.workhub.identity.*;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.util.*;

/** Shared database cursor, independent of optimistic business revisions. */
@Service
public class OverviewState {
    private final JdbcTemplate db;
    private final ProjectAccess access;

    public OverviewState(JdbcTemplate db, ProjectAccess access) {
        this.db = db;
        this.access = access;
    }

    public record State(
            String viewSeq,
            long businessRevision,
            String topologyRevision,
            String retainedAfterSeq,
            Map<String, Long> counts,
            long totalTasks,
            long totalEdges) {}

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public State state(ActorContext actor, UUID project) {
        access.read(actor, project);
        return read(project);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public void authorize(ActorContext actor, UUID project) {
        access.read(actor, project);
    }

    State read(UUID project) {
        return db
                .query(
                        """
                        SELECT s.*,p.business_revision FROM project_overview_state s
                        JOIN project p ON p.id=s.project_id WHERE s.project_id=?
                        """,
                        (r, n) -> {
                            var counts = new LinkedHashMap<String, Long>();
                            counts.put("TRIAGE", r.getLong("triage_count"));
                            counts.put("TODO", r.getLong("todo_count"));
                            counts.put("IN_PROGRESS", r.getLong("in_progress_count"));
                            counts.put("DONE", r.getLong("done_count"));
                            counts.put("CANCELED", r.getLong("canceled_count"));
                            return new State(
                                    r.getString("view_seq"),
                                    r.getLong("business_revision"),
                                    r.getString("topology_revision"),
                                    r.getString("retained_after_seq"),
                                    counts,
                                    counts.values().stream().mapToLong(Long::longValue).sum(),
                                    r.getLong("total_edges"));
                        },
                        bytes(project))
                .stream()
                .findFirst()
                .orElseThrow(ApiException::missing);
    }

    public static long sequence(String value) {
        if (value == null || !value.matches("0|[1-9][0-9]{0,18}"))
            throw ApiException.invalid("Invalid project sequence");
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw ApiException.invalid("Invalid project sequence");
        }
    }
}
