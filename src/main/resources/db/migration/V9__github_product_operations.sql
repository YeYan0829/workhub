CREATE TABLE github_operation (
 id BINARY(16) PRIMARY KEY, connection_id BINARY(16) NOT NULL, actor_id BINARY(16) NOT NULL,
 state VARCHAR(30) NOT NULL DEFAULT 'QUEUED', error_code VARCHAR(100), pages INT NOT NULL DEFAULT 0,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), completed_at DATETIME(6),
 FOREIGN KEY(connection_id) REFERENCES github_connection(id), FOREIGN KEY(actor_id) REFERENCES app_user(id), INDEX(connection_id,created_at)
);
CREATE TABLE github_operation_resource (
 operation_id BINARY(16) NOT NULL, resource_id BINARY(16) NOT NULL, requested_seq BIGINT NOT NULL,
 PRIMARY KEY(operation_id,resource_id), FOREIGN KEY(operation_id) REFERENCES github_operation(id),
 FOREIGN KEY(resource_id) REFERENCES external_resource(id)
);
