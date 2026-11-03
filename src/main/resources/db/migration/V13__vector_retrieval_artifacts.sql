ALTER TABLE retrieval_config
 ADD COLUMN identity_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin,
 ADD COLUMN model_contract JSON;
ALTER TABLE index_generation
 ADD COLUMN stage VARCHAR(12) COLLATE utf8mb4_0900_as_cs NOT NULL DEFAULT 'TEXT',
 ADD COLUMN canonical_generation_id BINARY(16),
 ADD CONSTRAINT generation_stage CHECK(stage IN ('TEXT','VECTOR')),
 ADD FOREIGN KEY(canonical_generation_id) REFERENCES index_generation(id);
CREATE TABLE embedding_artifact (
 id BINARY(16) PRIMARY KEY, project_id BINARY(16) NOT NULL, source_id BINARY(16) NOT NULL,
 config_id INT NOT NULL, input_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 dimensions INT NOT NULL, vector_bytes MEDIUMBLOB NOT NULL,
 vector_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE(source_id,config_id,input_hash),
 FOREIGN KEY(project_id,source_id) REFERENCES knowledge_source(project_id,id),
 FOREIGN KEY(config_id) REFERENCES retrieval_config(id),
 CHECK(dimensions>0 AND dimensions<=4096 AND OCTET_LENGTH(vector_bytes)=dimensions*4)
) ENGINE=InnoDB;
ALTER TABLE knowledge_chunk ADD COLUMN embedding_artifact_id BINARY(16),
 ADD FOREIGN KEY(embedding_artifact_id) REFERENCES embedding_artifact(id);
CREATE TABLE embedding_budget (
 run_id VARCHAR(100) COLLATE utf8mb4_0900_as_cs PRIMARY KEY,
 limit_nanousd BIGINT NOT NULL CHECK(limit_nanousd>=0),
 reserved_nanousd BIGINT NOT NULL DEFAULT 0 CHECK(reserved_nanousd>=0),
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB;
CREATE TABLE embedding_call (
 id BINARY(16) PRIMARY KEY, run_id VARCHAR(100) COLLATE utf8mb4_0900_as_cs NOT NULL,
 config_id INT NOT NULL, project_id BINARY(16) NOT NULL,
 purpose VARCHAR(12) NOT NULL, input_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 reserved_nanousd BIGINT NOT NULL, reported_tokens BIGINT, reported_nanousd BIGINT,
 state VARCHAR(12) NOT NULL DEFAULT 'RESERVED', duration_ms BIGINT, error_code VARCHAR(60),
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY(run_id) REFERENCES embedding_budget(run_id),
 FOREIGN KEY(config_id) REFERENCES retrieval_config(id), FOREIGN KEY(project_id) REFERENCES project(id),
 CHECK(state IN ('RESERVED','COMPLETE','ERROR','UNKNOWN'))
) ENGINE=InnoDB;
