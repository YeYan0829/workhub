-- Agent writes only its own run/audit/budget records; no business command or proposal in 5A.
CREATE TABLE agent_run (
 id BINARY(16) PRIMARY KEY,
 project_id BINARY(16) NOT NULL,
 actor_id BINARY(16) NOT NULL,
 question TEXT NOT NULL,
 model_contract JSON NOT NULL,
 business_revision BIGINT NOT NULL,
 status VARCHAR(24) NOT NULL DEFAULT 'QUEUED',
 attempt_token BINARY(16),
 result JSON,
 error_code VARCHAR(80),
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 completed_at DATETIME(6),
 FOREIGN KEY(project_id) REFERENCES project(id),
 FOREIGN KEY(actor_id) REFERENCES app_user(id),
 INDEX(project_id,actor_id,created_at),
 CHECK(status IN ('QUEUED','RUNNING','SUCCEEDED','INCOMPLETE','FAILED','STALE'))
);
CREATE TABLE agent_trace (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 run_id BINARY(16) NOT NULL,
 attempt_token BINARY(16) NOT NULL,
 kind VARCHAR(24) NOT NULL,
 payload JSON NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY(run_id) REFERENCES agent_run(id),
 INDEX(run_id,id)
);
CREATE TABLE agent_budget (
 authorization_id VARCHAR(100) PRIMARY KEY,
 contract_hash CHAR(64) NOT NULL,
 limit_nano_usd BIGINT NOT NULL,
 reserved_nano_usd BIGINT NOT NULL DEFAULT 0,
 CHECK(limit_nano_usd>=0 AND reserved_nano_usd>=0 AND reserved_nano_usd<=limit_nano_usd)
);
CREATE TABLE agent_model_call (
 id BINARY(16) PRIMARY KEY,
 run_id BINARY(16) NOT NULL,
 attempt_token BINARY(16) NOT NULL,
 authorization_id VARCHAR(100) NOT NULL,
 input_token_bound BIGINT NOT NULL,
 output_token_bound BIGINT NOT NULL,
 reserved_nano_usd BIGINT NOT NULL,
 input_tokens BIGINT,
 output_tokens BIGINT,
 estimated_nano_usd BIGINT,
 elapsed_millis BIGINT,
 error_code VARCHAR(80),
 state VARCHAR(16) NOT NULL DEFAULT 'RESERVED',
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY(run_id) REFERENCES agent_run(id),
 FOREIGN KEY(authorization_id) REFERENCES agent_budget(authorization_id),
 INDEX(run_id,created_at),
 CHECK(state IN ('RESERVED','COMPLETE','UNKNOWN'))
);
