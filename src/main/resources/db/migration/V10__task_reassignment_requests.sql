CREATE TABLE task_reassignment_request (
 id BINARY(16) PRIMARY KEY, project_id BINARY(16) NOT NULL, task_id BINARY(16) NOT NULL,
 requested_by BINARY(16) NOT NULL, reason VARCHAR(2000) NOT NULL,
 status VARCHAR(20) COLLATE utf8mb4_0900_as_cs NOT NULL DEFAULT 'PENDING',
 revision BIGINT NOT NULL DEFAULT 0, reviewed_by BINARY(16), decision_note VARCHAR(2000),
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), resolved_at DATETIME(6),
 pending_task_id BINARY(16) GENERATED ALWAYS AS (CASE WHEN status='PENDING' THEN task_id ELSE NULL END) STORED,
 UNIQUE(pending_task_id), INDEX(project_id,task_id,created_at),
 FOREIGN KEY(project_id,task_id) REFERENCES task(project_id,id),
 FOREIGN KEY(requested_by) REFERENCES app_user(id), FOREIGN KEY(reviewed_by) REFERENCES app_user(id),
 CHECK(status IN ('PENDING','APPROVED','REJECTED','SUPERSEDED')), CHECK(revision>=0)
) ENGINE=InnoDB;
