package dev.workhub.work;

import static dev.workhub.common.Ids.*;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.*;

@Repository
public class SubscriptionRepository {
    private final JdbcTemplate jdbc;

    public SubscriptionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void participate(UUID project, UUID task, UUID user, String reason) {
        if (user == null) return;
        // Keep an explicit unsubscribe inactive; participation never silently resubscribes.
        jdbc.update(
                """
INSERT INTO task_subscription(project_id,task_id,user_id,auto_reasons) VALUES (?,?,?,JSON_ARRAY(?))
ON DUPLICATE KEY UPDATE auto_reasons=IF(JSON_CONTAINS(auto_reasons,JSON_QUOTE(?)),auto_reasons,JSON_ARRAY_APPEND(auto_reasons,'$',?))
""",
                bytes(project),
                bytes(task),
                bytes(user),
                reason,
                reason,
                reason);
    }

    public void set(UUID project, UUID task, UUID user, boolean active) {
        jdbc.update(
                """
INSERT INTO task_subscription(project_id,task_id,user_id,active,auto_reasons) VALUES (?,?,?,?,JSON_ARRAY())
ON DUPLICATE KEY UPDATE active=VALUES(active),updated_at=CURRENT_TIMESTAMP(6)
""",
                bytes(project),
                bytes(task),
                bytes(user),
                active);
    }

    public List<UUID> subscribers(UUID project, UUID task) {
        return jdbc.query(
                "SELECT user_id FROM task_subscription WHERE project_id=? AND task_id=? AND active",
                (r, n) -> uuid(r.getBytes(1)),
                bytes(project),
                bytes(task));
    }
}
