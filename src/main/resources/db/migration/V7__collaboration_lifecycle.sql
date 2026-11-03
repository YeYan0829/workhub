ALTER TABLE app_user ADD email VARCHAR(254) UNIQUE, ADD email_verified BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE workspace ADD revision BIGINT NOT NULL DEFAULT 0;
ALTER TABLE workspace_member ADD role VARCHAR(20) COLLATE utf8mb4_0900_as_cs NOT NULL DEFAULT 'MEMBER', ADD CHECK(role IN ('ADMIN','MEMBER'));
-- Only the unambiguous legacy single-member space is automatically provisioned.
UPDATE workspace_member m JOIN (SELECT workspace_id,COUNT(*) n FROM workspace_member WHERE active GROUP BY workspace_id) x ON x.workspace_id=m.workspace_id SET m.role='ADMIN' WHERE m.active AND x.n=1;
ALTER TABLE project ADD description TEXT, ADD archived BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE task ADD triage_required BOOLEAN NOT NULL DEFAULT FALSE;
UPDATE task SET triage_required=TRUE WHERE status='TRIAGE';
ALTER TABLE comment ADD deleted BOOLEAN NOT NULL DEFAULT FALSE;
CREATE TABLE account_token (
 token_hash CHAR(64) CHARACTER SET ascii PRIMARY KEY, user_id BINARY(16) NOT NULL,
 purpose VARCHAR(20) NOT NULL, expires_at DATETIME(6) NOT NULL, used_at DATETIME(6),
 FOREIGN KEY(user_id) REFERENCES app_user(id), INDEX(expires_at)
);
CREATE TABLE workspace_invitation (
 id BINARY(16) PRIMARY KEY, workspace_id BINARY(16) NOT NULL, email VARCHAR(254) NOT NULL,
 token_hash CHAR(64) CHARACTER SET ascii UNIQUE NOT NULL, status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
 invited_by BINARY(16) NOT NULL, accepted_by BINARY(16), expires_at DATETIME(6) NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY(workspace_id) REFERENCES workspace(id), FOREIGN KEY(invited_by) REFERENCES app_user(id),
 CHECK(status IN ('PENDING','ACCEPTED','REVOKED')), INDEX(workspace_id,email,status)
);
CREATE TABLE management_activity (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, workspace_id BINARY(16) NOT NULL, project_id BINARY(16),
 actor_id BINARY(16) NOT NULL, kind VARCHAR(60) NOT NULL, detail JSON NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), INDEX(workspace_id,id),
 FOREIGN KEY(workspace_id) REFERENCES workspace(id), FOREIGN KEY(actor_id) REFERENCES app_user(id)
);
CREATE TABLE mail_delivery (
 id BINARY(16) PRIMARY KEY, recipient VARCHAR(254) NOT NULL, encrypted_body TEXT,
 status VARCHAR(20) NOT NULL DEFAULT 'QUEUED', error_code VARCHAR(60),
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), sent_at DATETIME(6)
);
CREATE TABLE rate_limit (
 bucket CHAR(64) CHARACTER SET ascii PRIMARY KEY, hits INT NOT NULL, expires_at DATETIME(6) NOT NULL, INDEX(expires_at)
);
CREATE TABLE github_installation_grant (
 installation_id BIGINT PRIMARY KEY, workspace_id BINARY(16) NOT NULL,
 FOREIGN KEY(workspace_id) REFERENCES workspace(id)
);
CREATE TABLE github_repository_grant (
 repository_id BIGINT PRIMARY KEY, installation_id BIGINT NOT NULL, repository_name VARCHAR(300) NOT NULL,
 FOREIGN KEY(installation_id) REFERENCES github_installation_grant(installation_id)
);
-- Existing connections are established mappings, not a new arbitrary-ID authorization.
INSERT INTO github_installation_grant SELECT c.installation_id,MIN(p.workspace_id) FROM github_connection c JOIN project p ON p.id=c.project_id GROUP BY c.installation_id HAVING COUNT(DISTINCT p.workspace_id)=1;
INSERT INTO github_repository_grant SELECT c.repository_id,c.installation_id,c.repository_name FROM github_connection c JOIN github_installation_grant g ON g.installation_id=c.installation_id;
