package dev.workhub.work;

import static dev.workhub.common.Ids.*;

import jakarta.annotation.PreDestroy;

import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.*;

/** Bounded operator housekeeping, independent of active subscriptions. */
@Component
public class OverviewMaintenanceJob {
    private static final Logger log = LoggerFactory.getLogger(OverviewMaintenanceJob.class);
    private final JdbcTemplate db;
    private final JournalMaintenance maintenance;
    private UUID after;
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(
                    r -> {
                        var t = new Thread(r, "overview-maintenance");
                        t.setDaemon(true);
                        return t;
                    });

    public OverviewMaintenanceJob(
            JdbcTemplate db,
            JournalMaintenance maintenance,
            @Value("${workhub.overview.maintenance-ms:60000}") long interval) {
        this.db = db;
        this.maintenance = maintenance;
        scheduler.scheduleWithFixedDelay(
                this::safeSweep,
                Math.max(1000, interval),
                Math.max(1000, interval),
                TimeUnit.MILLISECONDS);
    }

    private void safeSweep() {
        try {
            sweep();
        } catch (Exception e) {
            log.warn("OVERVIEW_MAINTENANCE_RETRY type={}", e.getClass().getSimpleName());
        }
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }

    public void sweep() {
        var ids =
                after == null
                        ? db.query(
                                "SELECT project_id FROM project_overview_state ORDER BY project_id"
                                    + " LIMIT 20",
                                (r, n) -> read(r, "project_id"))
                        : db.query(
                                "SELECT project_id FROM project_overview_state WHERE project_id>?"
                                    + " ORDER BY project_id LIMIT 20",
                                (r, n) -> read(r, "project_id"),
                                bytes(after));
        for (UUID project : ids) {
            try {
                maintenance.prune(project);
                if (!maintenance.reconcile(project, false)) {
                    log.warn("OVERVIEW_COUNTER_DRIFT project={}", project);
                    maintenance.reconcile(project, true);
                    log.info("OVERVIEW_COUNTER_REPAIRED project={}", project);
                }
            } catch (Exception e) {
                log.warn(
                        "OVERVIEW_MAINTENANCE_RETRY project={} type={}",
                        project,
                        e.getClass().getSimpleName());
            }
            after = project;
        }
        if (ids.size() < 20) after = null;
    }
}
