ALTER TABLE agent_run ADD triage_task_id BINARY(16), ADD triage_task_revision BIGINT,
 ADD triage_discussion_revision BIGINT, ADD FOREIGN KEY(triage_task_id) REFERENCES task(id);
CREATE TABLE agent_proposal (
 id BINARY(16) PRIMARY KEY, project_id BINARY(16) NOT NULL, run_id BINARY(16) NOT NULL UNIQUE,
 task_id BINARY(16) NOT NULL, latest_version INT NOT NULL, last_error VARCHAR(80),
 FOREIGN KEY(project_id) REFERENCES project(id), FOREIGN KEY(run_id) REFERENCES agent_run(id),
 FOREIGN KEY(task_id) REFERENCES task(id), INDEX(project_id,task_id)
);
-- Content and basis are append-only. Execution lives in a separate unique receipt.
CREATE TABLE agent_proposal_version (
 proposal_id BINARY(16) NOT NULL, version INT NOT NULL, content JSON NOT NULL,
 content_hash CHAR(64) NOT NULL, created_by BINARY(16) NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(proposal_id,version), FOREIGN KEY(proposal_id) REFERENCES agent_proposal(id)
);
CREATE TABLE agent_proposal_preview (
 token BINARY(16) PRIMARY KEY, proposal_id BINARY(16) NOT NULL, version INT NOT NULL,
 actor_id BINARY(16) NOT NULL, content_hash CHAR(64) NOT NULL,
 FOREIGN KEY(proposal_id,version) REFERENCES agent_proposal_version(proposal_id,version)
);
CREATE TABLE agent_proposal_execution (
 proposal_id BINARY(16) PRIMARY KEY, version INT NOT NULL, content_hash CHAR(64) NOT NULL,
 approved_by BINARY(16) NOT NULL, preview_token BINARY(16) NOT NULL,
 result JSON NOT NULL, executed_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY(proposal_id,version) REFERENCES agent_proposal_version(proposal_id,version)
);
