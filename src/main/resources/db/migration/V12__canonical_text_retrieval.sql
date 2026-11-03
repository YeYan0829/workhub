ALTER TABLE project_document ADD COLUMN deleted BOOLEAN NOT NULL DEFAULT FALSE;
UPDATE knowledge_source s JOIN comment c ON c.id=s.comment_id SET s.active=FALSE WHERE c.deleted;

CREATE TABLE retrieval_config (
 id INT PRIMARY KEY, chunker VARCHAR(100) NOT NULL, analyzer VARCHAR(100) NOT NULL
) ENGINE=InnoDB;
INSERT INTO retrieval_config VALUES (1,'lines-2000-utf16-overlap160-v1','identifier-cjk-bigram-v1');

CREATE TABLE knowledge_rebuild_batch (
 id BINARY(16) PRIMARY KEY, project_id BINARY(16) NOT NULL, result JSON NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY(project_id) REFERENCES project(id)
) ENGINE=InnoDB;

CREATE TABLE source_index_state (
 source_id BINARY(16) NOT NULL, config_id INT NOT NULL,
 desired_generation BIGINT NOT NULL DEFAULT 0, active_generation_id BINARY(16),
 PRIMARY KEY(source_id,config_id), FOREIGN KEY(source_id) REFERENCES knowledge_source(id),
 FOREIGN KEY(config_id) REFERENCES retrieval_config(id)
) ENGINE=InnoDB;
CREATE TABLE index_generation (
 id BINARY(16) PRIMARY KEY, source_id BINARY(16) NOT NULL, project_id BINARY(16) NOT NULL,
 config_id INT NOT NULL, generation_no BIGINT NOT NULL, source_revision BIGINT NOT NULL,
 source_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 manifest_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 chunk_count INT NOT NULL CHECK(chunk_count>0),
 build_job_id BINARY(16) NOT NULL UNIQUE,
 state VARCHAR(20) NOT NULL DEFAULT 'BUILDING' CHECK(state IN ('BUILDING','PUBLISHED','OBSOLETE','FAILED')),
 error_code VARCHAR(100), created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), published_at DATETIME(6),
 UNIQUE(source_id,config_id,generation_no), UNIQUE(source_id,config_id,id),
 FOREIGN KEY(project_id,source_id) REFERENCES knowledge_source(project_id,id),
 FOREIGN KEY(source_id,config_id) REFERENCES source_index_state(source_id,config_id),
 FOREIGN KEY(build_job_id) REFERENCES work_job(id)
) ENGINE=InnoDB;
ALTER TABLE source_index_state ADD CONSTRAINT active_generation_owner
 FOREIGN KEY(source_id,config_id,active_generation_id) REFERENCES index_generation(source_id,config_id,id);
CREATE TABLE knowledge_chunk (
 generation_id BINARY(16) NOT NULL, ordinal INT NOT NULL, title VARCHAR(500) NOT NULL,
 body MEDIUMTEXT NOT NULL, body_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 payload_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 char_start INT NOT NULL, char_end INT NOT NULL,
 PRIMARY KEY(generation_id,ordinal), FOREIGN KEY(generation_id) REFERENCES index_generation(id),
 CHECK(ordinal>=0 AND char_start>=0 AND char_end>char_start)
) ENGINE=InnoDB;
