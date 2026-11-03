-- CHECK comparisons must reject non-canonical casing even under a case-insensitive database default.
ALTER TABLE project_member MODIFY role VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL;
ALTER TABLE task
 MODIFY status VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 MODIFY type VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 MODIFY origin VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL;
ALTER TABLE project_document MODIFY kind VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL;
ALTER TABLE external_resource
 MODIFY kind VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 MODIFY availability VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'UNKNOWN';
ALTER TABLE activity MODIFY actor_type VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL;
ALTER TABLE work_job MODIFY status VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'WAITING';
ALTER TABLE outbox_message
 MODIFY publish_state VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PENDING',
 MODIFY consume_disposition VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'UNHANDLED';
