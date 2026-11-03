-- Qwen/Seed authorizations use CNY and independent ledgers. Existing GLM USD rows stay unchanged.
CREATE TABLE agent_provider_budget (
 authorization_id VARCHAR(100) PRIMARY KEY,
 contract_hash CHAR(64) NOT NULL,
 limit_nano_cny BIGINT NOT NULL,
 reserved_nano_cny BIGINT NOT NULL DEFAULT 0,
 CHECK(limit_nano_cny>=0 AND reserved_nano_cny>=0 AND reserved_nano_cny<=limit_nano_cny)
);
CREATE TABLE agent_provider_call (
 id BINARY(16) PRIMARY KEY,
 run_id BINARY(16) NOT NULL,
 attempt_token BINARY(16) NOT NULL,
 authorization_id VARCHAR(100) NOT NULL,
 input_token_bound BIGINT NOT NULL,
 output_token_bound BIGINT NOT NULL,
 reserved_nano_cny BIGINT NOT NULL,
 input_tokens BIGINT,
 output_tokens BIGINT,
 estimated_nano_cny BIGINT,
 elapsed_millis BIGINT,
 error_code VARCHAR(80),
 state VARCHAR(16) NOT NULL DEFAULT 'RESERVED',
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY(run_id) REFERENCES agent_run(id),
 FOREIGN KEY(authorization_id) REFERENCES agent_provider_budget(authorization_id),
 INDEX(run_id,created_at),
 CHECK(state IN ('RESERVED','COMPLETE','UNKNOWN'))
);
