CREATE TABLE csv_import (
 id BINARY(16) PRIMARY KEY, project_id BINARY(16) NOT NULL, actor_id BINARY(16) NOT NULL,
 filename VARCHAR(200) NOT NULL, input_hash CHAR(64) CHARACTER SET ascii NOT NULL, source MEDIUMBLOB,
 state VARCHAR(30) NOT NULL DEFAULT 'PREVIEW', stop_reason VARCHAR(80), generation INT NOT NULL DEFAULT 0,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), expires_at DATETIME(6) NOT NULL,
 FOREIGN KEY(project_id) REFERENCES project(id), FOREIGN KEY(actor_id) REFERENCES app_user(id), INDEX(project_id,created_at,id)
);
CREATE TABLE csv_import_row (
 import_id BINARY(16) NOT NULL, line_number INT NOT NULL, input JSON,
 state VARCHAR(20) NOT NULL, error_code VARCHAR(1000), task_id BINARY(16),
 PRIMARY KEY(import_id,line_number), FOREIGN KEY(import_id) REFERENCES csv_import(id),
 FOREIGN KEY(task_id) REFERENCES task(id), INDEX(import_id,state)
);
ALTER TABLE task ADD INDEX(project_id,due_date,id);
