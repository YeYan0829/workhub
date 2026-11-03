-- External comments have external identities; never impersonate local app_user/comment authors.
ALTER TABLE github_comment ADD COLUMN id BINARY(16), ADD COLUMN project_id BINARY(16);
UPDATE github_comment c JOIN github_issue i ON i.resource_id=c.resource_id SET c.id=UUID_TO_BIN(UUID()),c.project_id=i.project_id;
ALTER TABLE github_comment MODIFY id BINARY(16) NOT NULL, MODIFY project_id BINARY(16) NOT NULL,
 ADD UNIQUE(id), ADD UNIQUE(project_id,id),
 ADD FOREIGN KEY(project_id,resource_id) REFERENCES github_issue(project_id,resource_id);
ALTER TABLE knowledge_source ADD COLUMN github_comment_id BINARY(16), ADD UNIQUE(github_comment_id),
 ADD FOREIGN KEY(project_id,github_comment_id) REFERENCES github_comment(project_id,id),
 DROP CHECK knowledge_source_chk_1,
 ADD CONSTRAINT knowledge_source_one_owner CHECK((task_id IS NOT NULL)+(comment_id IS NOT NULL)+(document_id IS NOT NULL)+(github_comment_id IS NOT NULL)=1);
CREATE TABLE github_comment_seen (
 resource_id BINARY(16) NOT NULL, round_key VARCHAR(255) COLLATE utf8mb4_0900_as_cs NOT NULL, remote_id BIGINT NOT NULL,
 PRIMARY KEY(resource_id,round_key,remote_id), FOREIGN KEY(resource_id) REFERENCES github_issue(resource_id)
) ENGINE=InnoDB;
ALTER TABLE github_issue ADD COLUMN last_response JSON, ADD COLUMN last_request_id VARCHAR(255),
 ADD COLUMN next_recovery_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6);
ALTER TABLE sync_intent ADD COLUMN provider_request_id VARCHAR(255);
CREATE TABLE github_installation_lane (
 installation_id BIGINT PRIMARY KEY, owner_job BINARY(16), lease_until DATETIME(6),
 next_allowed_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB;
CREATE TABLE github_evidence (
 resource_id BINARY(16) PRIMARY KEY, project_id BINARY(16) NOT NULL, connection_id BINARY(16) NOT NULL,
 remote_number BIGINT NOT NULL, run_attempt INT, head_sha VARCHAR(64),
 FOREIGN KEY(project_id,resource_id) REFERENCES external_resource(project_id,id),
 FOREIGN KEY(project_id,connection_id) REFERENCES github_connection(project_id,id)
) ENGINE=InnoDB;
