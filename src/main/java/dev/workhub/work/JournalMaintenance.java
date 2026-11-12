package dev.workhub.work;

import static dev.workhub.common.Ids.*;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.util.*;

/** Run per project from an operator job; retain cursor movement and deletion atomically. */
@Service
public class JournalMaintenance {
    private final JdbcTemplate db;

    public JournalMaintenance(JdbcTemplate db) {
        this.db = db;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public long prune(UUID project) {
        lock(project);
        long seq =
                db.queryForObject(
                        "SELECT view_seq FROM project_overview_state WHERE project_id=? FOR UPDATE",
                        Long.class,
                        bytes(project));
        long aged =
                db.queryForObject(
                        "SELECT COALESCE(MAX(seq),0) FROM project_view_change WHERE project_id=?"
                            + " AND created_at<UTC_TIMESTAMP(6)-INTERVAL 24 HOUR",
                        Long.class,
                        bytes(project));
        long cutoff = Math.max(aged, Math.max(0, seq - 100000));
        db.update(
                "DELETE FROM project_view_change WHERE project_id=? AND seq<=?",
                bytes(project),
                cutoff);
        db.update(
                "UPDATE project_overview_state SET"
                    + " retained_after_seq=GREATEST(retained_after_seq,?) WHERE project_id=?",
                cutoff,
                bytes(project));
        return cutoff;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public boolean reconcile(UUID project, boolean repair) {
        lock(project);
        var counts = new HashMap<String, Long>();
        db.query(
                "SELECT status,COUNT(*) cnt FROM task WHERE project_id=? GROUP BY status",
                r -> {
                    counts.put(r.getString("status"), r.getLong("cnt"));
                },
                bytes(project));
        long edges =
                db.queryForObject(
                        "SELECT COUNT(*) FROM task_dependency WHERE project_id=?",
                        Long.class,
                        bytes(project));
        long triage = counts.getOrDefault("TRIAGE", 0L),
                todo = counts.getOrDefault("TODO", 0L),
                progress = counts.getOrDefault("IN_PROGRESS", 0L),
                done = counts.getOrDefault("DONE", 0L),
                canceled = counts.getOrDefault("CANCELED", 0L);
        boolean match =
                Boolean.TRUE.equals(
                        db.queryForObject(
                                "SELECT triage_count=? AND todo_count=? AND in_progress_count=? AND"
                                    + " done_count=? AND canceled_count=? AND total_edges=? FROM"
                                    + " project_overview_state WHERE project_id=?",
                                Boolean.class,
                                triage,
                                todo,
                                progress,
                                done,
                                canceled,
                                edges,
                                bytes(project)));
        if (!match && repair) {
            db.update(
                    "UPDATE project_overview_state SET"
                        + " triage_count=?,todo_count=?,in_progress_count=?,done_count=?,canceled_count=?,total_edges=?"
                        + " WHERE project_id=?",
                    triage,
                    todo,
                    progress,
                    done,
                    canceled,
                    edges,
                    bytes(project));
            db.update(
                    "CALL overview_change(?,'PROJECT_CHANGED',NULL,NULL,NULL,FALSE,FALSE,TRUE,JSON_ARRAY('counterRepair'))",
                    bytes(project));
        }
        return match;
    }

    private void lock(UUID project) {
        db.queryForList("SELECT id FROM project WHERE id=? FOR UPDATE", bytes(project));
    }
}
