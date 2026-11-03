-- Private, linear conversations. Financial rows retain their existing run foreign keys.
CREATE TABLE agent_conversation (
 id BINARY(16) PRIMARY KEY, owner_id BINARY(16) NOT NULL, project_id BINARY(16) NOT NULL,
 title VARCHAR(120) NOT NULL, archived BOOLEAN NOT NULL DEFAULT FALSE,
 deleted_at DATETIME(6), cleanup_state VARCHAR(24) NOT NULL DEFAULT 'LIVE',
 history_revision BIGINT NOT NULL DEFAULT 0, last_seq BIGINT NOT NULL DEFAULT 0,
 active_turn_id BINARY(16), summary_version BIGINT NOT NULL DEFAULT 0,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 last_activity_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY(owner_id) REFERENCES app_user(id), FOREIGN KEY(project_id) REFERENCES project(id),
 INDEX(owner_id,last_activity_at,id)
);
CREATE TABLE agent_turn (
 id BINARY(16) PRIMARY KEY, conversation_id BINARY(16) NOT NULL, seq BIGINT NOT NULL,
 active_run_id BINARY(16), status VARCHAR(24) NOT NULL DEFAULT 'QUEUED', error_code VARCHAR(80),
 context_head_seq BIGINT NOT NULL, created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY(conversation_id) REFERENCES agent_conversation(id), UNIQUE(conversation_id,seq)
);
CREATE TABLE agent_message (
 id BINARY(16) PRIMARY KEY, conversation_id BINARY(16) NOT NULL, turn_id BINARY(16) NOT NULL,
 role VARCHAR(16) NOT NULL, body MEDIUMTEXT NOT NULL, created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY(conversation_id) REFERENCES agent_conversation(id), FOREIGN KEY(turn_id) REFERENCES agent_turn(id),
 UNIQUE(turn_id,role), CHECK(role IN ('USER','ASSISTANT'))
);
CREATE TABLE agent_turn_receipt (
 conversation_id BINARY(16) NOT NULL, request_key VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
 request_hash CHAR(64) NOT NULL, turn_id BINARY(16) NOT NULL, run_id BINARY(16) NOT NULL,
 PRIMARY KEY(conversation_id,request_key), FOREIGN KEY(conversation_id) REFERENCES agent_conversation(id)
);
CREATE TABLE agent_context_summary (
 conversation_id BINARY(16) NOT NULL, version BIGINT NOT NULL, through_seq BIGINT NOT NULL,
 previous_version BIGINT NOT NULL, input_digest CHAR(64) NOT NULL, body JSON NOT NULL,
 dependencies JSON NOT NULL, model VARCHAR(100) NOT NULL, prompt_version VARCHAR(40) NOT NULL,
 run_id BINARY(16) NOT NULL, valid BOOLEAN NOT NULL DEFAULT TRUE,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(conversation_id,version), FOREIGN KEY(conversation_id) REFERENCES agent_conversation(id)
);
CREATE TABLE agent_run_checkpoint (
 run_id BINARY(16) PRIMARY KEY, attempt_token BINARY(16) NOT NULL, format_version INT NOT NULL,
 call_count INT NOT NULL, summary_version BIGINT NOT NULL, history_revision BIGINT NOT NULL,
 payload MEDIUMTEXT NOT NULL, payload_hash CHAR(64) NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), FOREIGN KEY(run_id) REFERENCES agent_run(id)
);
ALTER TABLE agent_run ADD COLUMN conversation_id BINARY(16), ADD COLUMN turn_id BINARY(16),
 ADD COLUMN stop_requested BOOLEAN NOT NULL DEFAULT FALSE, ADD COLUMN content_deleted BOOLEAN NOT NULL DEFAULT FALSE,
 ADD COLUMN object_bindings JSON, ADD COLUMN context_manifest JSON,
 ADD INDEX(conversation_id,created_at), ADD FOREIGN KEY(conversation_id) REFERENCES agent_conversation(id),
 ADD FOREIGN KEY(turn_id) REFERENCES agent_turn(id);
ALTER TABLE agent_model_call ADD COLUMN purpose VARCHAR(24) NOT NULL DEFAULT 'ANSWER';
ALTER TABLE agent_provider_call ADD COLUMN purpose VARCHAR(24) NOT NULL DEFAULT 'ANSWER';
-- Legacy runs are deliberately migrated on demand, one run per conversation; no model calls.
