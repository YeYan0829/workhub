-- Phase 3: fixed repository scope, durable reception and externally observable intent.
CREATE TABLE github_connection (
 id BINARY(16) PRIMARY KEY, project_id BINARY(16) NOT NULL,
 installation_id BIGINT NOT NULL CHECK(installation_id>0), repository_id BIGINT NOT NULL CHECK(repository_id>0),
 repository_name VARCHAR(255) COLLATE utf8mb4_0900_as_cs NOT NULL,
 import_actor BINARY(16) NOT NULL, enabled BOOLEAN NOT NULL DEFAULT TRUE,
 scan_watermark DATETIME(6), next_scan_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 next_audit_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE(repository_id), UNIQUE(project_id,id),
 FOREIGN KEY(project_id) REFERENCES project(id), FOREIGN KEY(import_actor) REFERENCES app_user(id)
) ENGINE=InnoDB;
CREATE TABLE webhook_inbox (
 id BINARY(16) PRIMARY KEY, connection_id BINARY(16) NOT NULL,
 delivery_id VARCHAR(128) COLLATE utf8mb4_0900_as_cs NOT NULL,
 payload_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, raw_body MEDIUMBLOB NOT NULL,
 event_type VARCHAR(80) NOT NULL, action VARCHAR(80) NOT NULL, status VARCHAR(20) NOT NULL,
 hash_conflict BOOLEAN NOT NULL DEFAULT FALSE, accepted_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE(connection_id,delivery_id), FOREIGN KEY(connection_id) REFERENCES github_connection(id),
 CHECK(status IN ('ACCEPTED','IGNORED','ROUTED'))
) ENGINE=InnoDB;
CREATE TABLE github_issue (
 resource_id BINARY(16) PRIMARY KEY, project_id BINARY(16) NOT NULL, connection_id BINARY(16) NOT NULL,
 remote_id BIGINT NOT NULL CHECK(remote_id>0), issue_number BIGINT NOT NULL CHECK(issue_number>0), task_id BINARY(16),
 sync_revision BIGINT NOT NULL DEFAULT 0, snapshot JSON, remote_updated_at DATETIME(6),
 etag VARCHAR(255), error_code VARCHAR(100), error_since DATETIME(6), error_notified BOOLEAN NOT NULL DEFAULT FALSE,
 UNIQUE(connection_id,remote_id), UNIQUE(connection_id,issue_number), UNIQUE(task_id), UNIQUE(project_id,resource_id),
 FOREIGN KEY(project_id,resource_id) REFERENCES external_resource(project_id,id),
 FOREIGN KEY(project_id,connection_id) REFERENCES github_connection(project_id,id),
 FOREIGN KEY(project_id,task_id) REFERENCES task(project_id,id)
) ENGINE=InnoDB;
CREATE TABLE sync_intent (
 id BINARY(16) PRIMARY KEY, project_id BINARY(16) NOT NULL, resource_id BINARY(16) NOT NULL,
 actor_id BINARY(16) NOT NULL, task_revision BIGINT NOT NULL, sync_revision BIGINT NOT NULL,
 base_snapshot JSON NOT NULL, desired_patch JSON NOT NULL,
 state VARCHAR(24) COLLATE utf8mb4_0900_as_cs NOT NULL DEFAULT 'QUEUED',
 send_started_at DATETIME(6), verify_count INT NOT NULL DEFAULT 0,
 error_code VARCHAR(100), attribution VARCHAR(40) NOT NULL DEFAULT 'UNCONFIRMED',
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 active_resource BINARY(16) GENERATED ALWAYS AS
 (CASE WHEN state IN ('QUEUED','PRECHECK','RETRY_WAIT','SEND_STARTED','VERIFYING','UNKNOWN') THEN resource_id ELSE NULL END) STORED,
 UNIQUE(active_resource), FOREIGN KEY(project_id,resource_id) REFERENCES github_issue(project_id,resource_id),
 FOREIGN KEY(actor_id) REFERENCES app_user(id),
 CHECK(state IN ('QUEUED','PRECHECK','RETRY_WAIT','SEND_STARTED','VERIFYING','UNKNOWN','SATISFIED','APPLIED','CONFLICT','ERROR'))
) ENGINE=InnoDB;
CREATE TABLE github_comment (
 resource_id BINARY(16) NOT NULL, remote_id BIGINT NOT NULL, body MEDIUMTEXT NOT NULL,
 remote_login VARCHAR(255) NOT NULL, remote_updated_at DATETIME(6) NOT NULL,
 revision BIGINT NOT NULL DEFAULT 0, active BOOLEAN NOT NULL DEFAULT TRUE,
 PRIMARY KEY(resource_id,remote_id), FOREIGN KEY(resource_id) REFERENCES github_issue(resource_id)
) ENGINE=InnoDB;
