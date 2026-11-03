CREATE TABLE project_triage_settings (
 project_id BINARY(16) PRIMARY KEY,
 enabled BOOLEAN NOT NULL DEFAULT FALSE,
 strategy VARCHAR(16) NOT NULL DEFAULT 'WORKFLOW',
 revision BIGINT NOT NULL DEFAULT 1,
 updated_by BINARY(16) NOT NULL,
 FOREIGN KEY(project_id) REFERENCES project(id),
 FOREIGN KEY(updated_by) REFERENCES app_user(id),
 CHECK(strategy IN ('WORKFLOW','GUIDED','AGENT'))
);
-- NULL for manual runs. A newly submitted task can create at most one automatic run.
ALTER TABLE agent_run ADD automatic_task_id BINARY(16) NULL,
 ADD UNIQUE KEY uq_automatic_triage(automatic_task_id),
 ADD FOREIGN KEY(automatic_task_id) REFERENCES task(id);
