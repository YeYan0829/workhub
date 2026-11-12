-- V23 monetary ledgers remain historical. New executions never reserve or settle money.
ALTER TABLE project_model_policy MODIFY max_cost_nanos BIGINT NULL DEFAULT NULL;

CREATE TABLE team_model_execution (
 run_id BINARY(16) PRIMARY KEY,
 workspace_id BINARY(16) NOT NULL,
 project_id BINARY(16) NOT NULL,
 connection_version BIGINT NOT NULL,
 policy_version BIGINT NOT NULL,
 provider VARCHAR(16) NOT NULL,
 model VARCHAR(80) NOT NULL,
 max_calls INT NOT NULL,
 max_millis INT NOT NULL,
 call_count INT NOT NULL DEFAULT 0,
 trigger_source VARCHAR(24) NOT NULL,
 FOREIGN KEY(run_id) REFERENCES agent_run(id),
 FOREIGN KEY(workspace_id) REFERENCES team_model_connection(workspace_id),
 FOREIGN KEY(project_id) REFERENCES project(id),
 CHECK(call_count BETWEEN 0 AND max_calls),
 CHECK(max_calls BETWEEN 1 AND 20 AND max_millis BETWEEN 1000 AND 300000)
);

CREATE TABLE team_model_usage (
 id BINARY(16) PRIMARY KEY,
 run_id BINARY(16) NOT NULL,
 attempt_token BINARY(16) NOT NULL,
 workspace_id BINARY(16) NOT NULL,
 project_id BINARY(16) NOT NULL,
 period_start DATE NOT NULL,
 connection_version BIGINT NOT NULL,
 provider VARCHAR(16) NOT NULL,
 model VARCHAR(80) NOT NULL,
 input_token_bound BIGINT NOT NULL,
 output_token_bound BIGINT NOT NULL,
 input_tokens BIGINT,
 output_tokens BIGINT,
 state VARCHAR(16) NOT NULL DEFAULT 'PENDING',
 usage_state VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN',
 purpose VARCHAR(16) NOT NULL,
 elapsed_millis BIGINT,
 error_code VARCHAR(80),
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY(run_id) REFERENCES team_model_execution(run_id),
 FOREIGN KEY(workspace_id) REFERENCES team_model_connection(workspace_id),
 FOREIGN KEY(project_id) REFERENCES project(id),
 INDEX(workspace_id,period_start,project_id,id),
 INDEX(run_id,purpose),
 CHECK(state IN ('PENDING','COMPLETE','UNKNOWN','NOT_SENT')),
 CHECK(usage_state IN ('KNOWN','PARTIAL','UNKNOWN','NOT_APPLICABLE')),
 CHECK(input_tokens IS NULL OR input_tokens>=0),
 CHECK(output_tokens IS NULL OR output_tokens>=0)
);
