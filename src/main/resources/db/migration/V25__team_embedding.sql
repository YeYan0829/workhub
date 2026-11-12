-- Team embedding never writes the V13 monetary ledger or adopts its deployment credentials.
CREATE TABLE team_embedding_sequence (id INT PRIMARY KEY, next_config BIGINT NOT NULL);
INSERT INTO team_embedding_sequence VALUES (1,1500000000);
CREATE TABLE team_embedding_space (
 config_id INT PRIMARY KEY,
 workspace_id BINARY(16) NOT NULL,
 identity_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 model VARCHAR(80) NOT NULL,
 dimensions INT NOT NULL,
 query_instruction VARCHAR(1000) NOT NULL,
 index_name VARCHAR(100) NOT NULL UNIQUE,
 created_by BINARY(16) NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE(workspace_id,identity_hash),
 UNIQUE(workspace_id,config_id),
 FOREIGN KEY(config_id) REFERENCES retrieval_config(id),
 FOREIGN KEY(workspace_id) REFERENCES workspace(id),
 FOREIGN KEY(created_by) REFERENCES app_user(id),
 CHECK(dimensions BETWEEN 64 AND 2048)
);
CREATE TABLE team_embedding_connection (
 workspace_id BINARY(16) PRIMARY KEY,
 version BIGINT NOT NULL,
 enabled BOOLEAN NOT NULL,
 use_generation_key BOOLEAN NOT NULL,
 key_cipher TEXT,
 default_config_id INT,
 request_rate DOUBLE NOT NULL,
 request_burst INT NOT NULL,
 updated_by BINARY(16) NOT NULL,
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY(workspace_id) REFERENCES workspace(id),
 FOREIGN KEY(workspace_id,default_config_id) REFERENCES team_embedding_space(workspace_id,config_id),
 FOREIGN KEY(updated_by) REFERENCES app_user(id),
 CHECK(version>0 AND request_rate BETWEEN 0.001 AND 10000 AND request_burst BETWEEN 1 AND 10000),
 CHECK(use_generation_key OR key_cipher IS NOT NULL)
);
CREATE TABLE project_embedding_policy (
 project_id BINARY(16) PRIMARY KEY,
 workspace_id BINARY(16) NOT NULL,
 version BIGINT NOT NULL,
 enabled BOOLEAN NOT NULL,
 active_config_id INT,
 building_config_id INT,
 updated_by BINARY(16) NOT NULL,
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY(project_id) REFERENCES project(id),
 FOREIGN KEY(workspace_id,active_config_id) REFERENCES team_embedding_space(workspace_id,config_id),
 FOREIGN KEY(workspace_id,building_config_id) REFERENCES team_embedding_space(workspace_id,config_id),
 FOREIGN KEY(updated_by) REFERENCES app_user(id)
);
CREATE TABLE team_embedding_operation (
 id BINARY(16) PRIMARY KEY,
 project_id BINARY(16) NOT NULL,
 config_id INT NOT NULL,
 calls INT NOT NULL DEFAULT 0,
 FOREIGN KEY(project_id) REFERENCES project(id),
 FOREIGN KEY(config_id) REFERENCES team_embedding_space(config_id),
 CHECK(calls BETWEEN 0 AND 3)
);
CREATE TABLE team_embedding_call (
 id BINARY(16) PRIMARY KEY,
 operation_id BINARY(16) NOT NULL,
 workspace_id BINARY(16) NOT NULL,
 project_id BINARY(16) NOT NULL,
 config_id INT NOT NULL,
 actor_id BINARY(16),
 connection_version BIGINT NOT NULL,
 generation_connection_version BIGINT,
 purpose VARCHAR(12) NOT NULL,
 input_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 input_bound INT NOT NULL,
 input_tokens BIGINT,
 state VARCHAR(16) NOT NULL DEFAULT 'PENDING',
 usage_state VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN',
 http_status INT,
 duration_ms BIGINT,
 wait_ms BIGINT,
 permit_at BIGINT,
 error_code VARCHAR(80),
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY(operation_id) REFERENCES team_embedding_operation(id),
 FOREIGN KEY(workspace_id,config_id) REFERENCES team_embedding_space(workspace_id,config_id),
 FOREIGN KEY(project_id) REFERENCES project(id),
 FOREIGN KEY(actor_id) REFERENCES app_user(id),
 INDEX(workspace_id,created_at,project_id),
 CHECK(state IN ('PENDING','COMPLETE','UNKNOWN','NOT_SENT')),
 CHECK(usage_state IN ('KNOWN','UNKNOWN','NOT_APPLICABLE')),
 CHECK(input_tokens IS NULL OR input_tokens>=0)
);
