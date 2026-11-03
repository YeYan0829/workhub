-- Production schema. Phase 0 probes remain in test resources only.
-- UUID bytes use the Java UUID most/least-significant-bit order (no swap).
CREATE TABLE app_user (
 id BINARY(16) PRIMARY KEY, login VARCHAR(100) COLLATE utf8mb4_0900_as_cs NOT NULL UNIQUE,
 display_name VARCHAR(200) NOT NULL, password_hash VARCHAR(255) NOT NULL,
 active BOOLEAN NOT NULL DEFAULT TRUE, auth_revision BIGINT NOT NULL DEFAULT 0 CHECK(auth_revision>=0)
) ENGINE=InnoDB;
CREATE TABLE workspace (id BINARY(16) PRIMARY KEY, name VARCHAR(200) NOT NULL) ENGINE=InnoDB;
CREATE TABLE workspace_member (
 workspace_id BINARY(16) NOT NULL, user_id BINARY(16) NOT NULL,
 active BOOLEAN NOT NULL DEFAULT TRUE, revision BIGINT NOT NULL DEFAULT 0,
 PRIMARY KEY(workspace_id,user_id), FOREIGN KEY(workspace_id) REFERENCES workspace(id),
 FOREIGN KEY(user_id) REFERENCES app_user(id), CHECK(revision>=0)
) ENGINE=InnoDB;
CREATE TABLE project (
 id BINARY(16) PRIMARY KEY, workspace_id BINARY(16) NOT NULL, name VARCHAR(200) NOT NULL,
 business_revision BIGINT NOT NULL DEFAULT 0 CHECK(business_revision>=0),
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), UNIQUE(workspace_id,id),
 FOREIGN KEY(workspace_id) REFERENCES workspace(id)
) ENGINE=InnoDB;
CREATE TABLE project_member (
 project_id BINARY(16) NOT NULL, user_id BINARY(16) NOT NULL,
 role VARCHAR(20) NOT NULL CHECK(role IN ('MEMBER','MAINTAINER')),
 active BOOLEAN NOT NULL DEFAULT TRUE, revision BIGINT NOT NULL DEFAULT 0 CHECK(revision>=0),
 PRIMARY KEY(project_id,user_id), FOREIGN KEY(project_id) REFERENCES project(id),
 FOREIGN KEY(user_id) REFERENCES app_user(id), INDEX(user_id,active,project_id)
) ENGINE=InnoDB;
CREATE TABLE task (
 id BINARY(16) PRIMARY KEY, project_id BINARY(16) NOT NULL,
 title VARCHAR(500) NOT NULL, description MEDIUMTEXT NOT NULL,
 status VARCHAR(20) NOT NULL CHECK(status IN ('TRIAGE','TODO','IN_PROGRESS','DONE','CANCELED')),
 type VARCHAR(20) NOT NULL CHECK(type IN ('BUG','FEATURE','QUESTION','TASK')),
 priority INT NOT NULL DEFAULT 2 CHECK(priority BETWEEN 0 AND 3), assignee_id BINARY(16), due_date DATE,
 origin VARCHAR(20) NOT NULL CHECK(origin IN ('MANUAL','AGENT','GITHUB')),
 revision BIGINT NOT NULL DEFAULT 0 CHECK(revision>=0), discussion_revision BIGINT NOT NULL DEFAULT 0 CHECK(discussion_revision>=0),
 created_by BINARY(16) NOT NULL, created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), UNIQUE(project_id,id),
 FOREIGN KEY(project_id) REFERENCES project(id),
 FOREIGN KEY(project_id,assignee_id) REFERENCES project_member(project_id,user_id),
 FOREIGN KEY(created_by) REFERENCES app_user(id),
 INDEX(project_id,updated_at DESC,id DESC), INDEX(project_id,status,updated_at DESC,id DESC),
 INDEX(project_id,assignee_id,updated_at DESC,id DESC), INDEX(assignee_id,project_id,id),
 INDEX(project_id,priority,updated_at DESC,id DESC)
) ENGINE=InnoDB;
CREATE TABLE task_dependency (
 project_id BINARY(16) NOT NULL, blocker_id BINARY(16) NOT NULL, blocked_id BINARY(16) NOT NULL,
 created_by BINARY(16) NOT NULL, created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(project_id,blocker_id,blocked_id), CHECK(blocker_id<>blocked_id),
 FOREIGN KEY(project_id,blocker_id) REFERENCES task(project_id,id),
 FOREIGN KEY(project_id,blocked_id) REFERENCES task(project_id,id),
 FOREIGN KEY(created_by) REFERENCES app_user(id), INDEX(project_id,blocked_id,blocker_id)
) ENGINE=InnoDB;
CREATE TABLE comment (
 id BINARY(16) PRIMARY KEY, project_id BINARY(16) NOT NULL, task_id BINARY(16) NOT NULL,
 author_id BINARY(16) NOT NULL, body MEDIUMTEXT NOT NULL, revision BIGINT NOT NULL DEFAULT 0 CHECK(revision>=0),
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), UNIQUE(project_id,id),
 FOREIGN KEY(project_id,task_id) REFERENCES task(project_id,id), FOREIGN KEY(author_id) REFERENCES app_user(id),
 INDEX(project_id,task_id,created_at,id)
) ENGINE=InnoDB;
CREATE TABLE task_subscription (
 project_id BINARY(16) NOT NULL, task_id BINARY(16) NOT NULL, user_id BINARY(16) NOT NULL,
 active BOOLEAN NOT NULL DEFAULT TRUE, auto_reasons JSON NOT NULL,
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), PRIMARY KEY(project_id,task_id,user_id),
 FOREIGN KEY(project_id,task_id) REFERENCES task(project_id,id),
 FOREIGN KEY(project_id,user_id) REFERENCES project_member(project_id,user_id)
) ENGINE=InnoDB;
CREATE TABLE project_document (
 id BINARY(16) PRIMARY KEY, project_id BINARY(16) NOT NULL,
 kind VARCHAR(20) NOT NULL CHECK(kind IN ('PLAN','MEETING_NOTES','REPORT','DOCUMENT')),
 title VARCHAR(500) NOT NULL, body MEDIUMTEXT NOT NULL, revision BIGINT NOT NULL DEFAULT 0 CHECK(revision>=0),
 created_by BINARY(16) NOT NULL, updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE(project_id,id), FOREIGN KEY(project_id) REFERENCES project(id), FOREIGN KEY(created_by) REFERENCES app_user(id),
 INDEX(project_id,updated_at DESC,id DESC)
) ENGINE=InnoDB;
CREATE TABLE task_document (
 project_id BINARY(16) NOT NULL, task_id BINARY(16) NOT NULL, document_id BINARY(16) NOT NULL,
 PRIMARY KEY(project_id,task_id,document_id), FOREIGN KEY(project_id,task_id) REFERENCES task(project_id,id),
 FOREIGN KEY(project_id,document_id) REFERENCES project_document(project_id,id)
) ENGINE=InnoDB;
-- URL references only in Phase 1; provider refresh fields are reserved, never scheduled here.
CREATE TABLE external_resource (
 id BINARY(16) PRIMARY KEY, project_id BINARY(16) NOT NULL, kind VARCHAR(20) NOT NULL,
 provider_key VARCHAR(512) COLLATE utf8mb4_0900_as_cs NOT NULL, url VARCHAR(2048) NOT NULL,
 confirmed_metadata JSON, observed_revision BIGINT NOT NULL DEFAULT 0, confirmed_at DATETIME(6),
 availability VARCHAR(20) NOT NULL DEFAULT 'UNKNOWN', requested_seq BIGINT NOT NULL DEFAULT 0,
 applied_seq BIGINT NOT NULL DEFAULT 0, lane_generation BIGINT NOT NULL DEFAULT 0, lease_until DATETIME(6),
 UNIQUE(project_id,id), UNIQUE(project_id,kind,provider_key), FOREIGN KEY(project_id) REFERENCES project(id),
 CHECK(kind IN ('URL','ISSUE','PR','COMMIT','CI_RUN')), CHECK(availability IN ('UNKNOWN','AVAILABLE','UNAVAILABLE'))
) ENGINE=InnoDB;
CREATE TABLE task_resource (
 project_id BINARY(16) NOT NULL, task_id BINARY(16) NOT NULL, resource_id BINARY(16) NOT NULL,
 PRIMARY KEY(project_id,task_id,resource_id), FOREIGN KEY(project_id,task_id) REFERENCES task(project_id,id),
 FOREIGN KEY(project_id,resource_id) REFERENCES external_resource(project_id,id)
) ENGINE=InnoDB;
CREATE TABLE activity (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, project_id BINARY(16) NOT NULL, task_id BINARY(16),
 fact_key VARCHAR(255) COLLATE utf8mb4_0900_as_cs NOT NULL, kind VARCHAR(60) NOT NULL,
 actor_type VARCHAR(20) NOT NULL CHECK(actor_type IN ('HUMAN','APPROVED_AGENT','GITHUB_SYNC')),
 actor_id BINARY(16), cause_id BINARY(16) NOT NULL, before_state JSON, after_state JSON,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), UNIQUE(project_id,id), UNIQUE(project_id,fact_key),
 FOREIGN KEY(project_id) REFERENCES project(id), FOREIGN KEY(project_id,task_id) REFERENCES task(project_id,id),
 FOREIGN KEY(actor_id) REFERENCES app_user(id), INDEX(project_id,id DESC), INDEX(project_id,task_id,id DESC)
) ENGINE=InnoDB;
CREATE TABLE notification (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, project_id BINARY(16) NOT NULL, activity_id BIGINT NOT NULL,
 recipient_id BINARY(16) NOT NULL, reason_set JSON NOT NULL, read_at DATETIME(6),
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), UNIQUE(activity_id,recipient_id),
 FOREIGN KEY(project_id,activity_id) REFERENCES activity(project_id,id),
 FOREIGN KEY(project_id,recipient_id) REFERENCES project_member(project_id,user_id),
 INDEX(recipient_id,id DESC), INDEX(recipient_id,read_at,id DESC)
) ENGINE=InnoDB;
-- Durable HTTP create receipts implement §6 Idempotency-Key. No transport logic.
CREATE TABLE operation_receipt (
 actor_id BINARY(16) NOT NULL, request_key VARCHAR(128) COLLATE utf8mb4_0900_as_cs NOT NULL,
 project_id BINARY(16), request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 result_id BINARY(16) NOT NULL, created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(actor_id,request_key), FOREIGN KEY(actor_id) REFERENCES app_user(id),
 FOREIGN KEY(project_id) REFERENCES project(id)
) ENGINE=InnoDB;
-- Only owner metadata and persistent index obligations; Phase 2+ implements consumers/indexing.
CREATE TABLE knowledge_source (
 id BINARY(16) PRIMARY KEY, project_id BINARY(16) NOT NULL, task_id BINARY(16), comment_id BINARY(16), document_id BINARY(16),
 source_revision BIGINT NOT NULL, body_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 canonical_url VARCHAR(2048) NOT NULL, active BOOLEAN NOT NULL DEFAULT TRUE,
 UNIQUE(project_id,id), UNIQUE(task_id), UNIQUE(comment_id), UNIQUE(document_id),
 CHECK((task_id IS NOT NULL)+(comment_id IS NOT NULL)+(document_id IS NOT NULL)=1),
 FOREIGN KEY(project_id,task_id) REFERENCES task(project_id,id),
 FOREIGN KEY(project_id,comment_id) REFERENCES comment(project_id,id),
 FOREIGN KEY(project_id,document_id) REFERENCES project_document(project_id,id)
) ENGINE=InnoDB;
CREATE TABLE work_job (
 id BINARY(16) PRIMARY KEY, kind VARCHAR(60) NOT NULL, handler VARCHAR(60) NOT NULL,
 job_key VARCHAR(255) COLLATE utf8mb4_0900_as_cs NOT NULL UNIQUE, project_id BINARY(16), owner_ref BINARY(16) NOT NULL,
 payload JSON NOT NULL, payload_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 status VARCHAR(20) NOT NULL DEFAULT 'WAITING' CHECK(status IN ('WAITING','RUNNING','RETRY_WAIT','DONE','FAILED')),
 dispatch_seq BIGINT NOT NULL DEFAULT 0, attempt INT NOT NULL DEFAULT 0,
 next_attempt_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), lease_token BINARY(16), lease_until DATETIME(6),
 last_progress_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), last_error TEXT,
 completed_at DATETIME(6), result_ref VARCHAR(512), result_hash CHAR(64),
 FOREIGN KEY(project_id) REFERENCES project(id),
 CHECK(status<>'DONE' OR (completed_at IS NOT NULL AND result_ref IS NOT NULL AND result_hash IS NOT NULL)),
 INDEX(status,last_progress_at,id), INDEX(status,lease_until,id)
) ENGINE=InnoDB;
CREATE TABLE outbox_message (
 id BINARY(16) PRIMARY KEY, job_id BINARY(16), dispatch_seq BIGINT NOT NULL,
 destination VARCHAR(100) NOT NULL, expected_handler VARCHAR(60) NOT NULL, schema_version INT NOT NULL,
 payload JSON NOT NULL, payload_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 available_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 publish_state VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK(publish_state IN ('PENDING','CLAIMED','PUBLISHED')),
 claim_token BINARY(16), claim_until DATETIME(6), confirmed_at DATETIME(6),
 consume_disposition VARCHAR(20) NOT NULL DEFAULT 'UNHANDLED'
 CHECK(consume_disposition IN ('UNHANDLED','SUCCEEDED','RETRY_SCHEDULED','FAILED','SUPERSEDED')),
 handled_at DATETIME(6), next_message_id BINARY(16), error_summary TEXT,
 UNIQUE(job_id,dispatch_seq,destination), FOREIGN KEY(job_id) REFERENCES work_job(id),
 FOREIGN KEY(next_message_id) REFERENCES outbox_message(id),
 CHECK(consume_disposition='UNHANDLED' OR handled_at IS NOT NULL),
 CHECK(consume_disposition<>'RETRY_SCHEDULED' OR next_message_id IS NOT NULL),
 INDEX(publish_state,available_at,id), INDEX(publish_state,claim_until,id)
) ENGINE=InnoDB;
