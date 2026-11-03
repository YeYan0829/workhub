-- Preserve V1-V3. JSON storage normalizes whitespace; retain exact transport bytes separately.
ALTER TABLE outbox_message
 ADD wire_payload MEDIUMTEXT,
 ADD publish_attempt INT NOT NULL DEFAULT 0 CHECK(publish_attempt>=0),
 ADD last_publish_error VARCHAR(1000),
 ADD created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 ADD updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6);
UPDATE outbox_message SET wire_payload=CAST(payload AS CHAR), payload_hash=SHA2(CAST(payload AS CHAR),256);
ALTER TABLE outbox_message MODIFY wire_payload MEDIUMTEXT NOT NULL;
ALTER TABLE work_job
 ADD created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 ADD CONSTRAINT work_job_nonnegative CHECK(dispatch_seq>=0 AND attempt>=0);
CREATE TABLE failure_record (
 id BINARY(16) PRIMARY KEY,
 failure_key VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
 job_id BINARY(16), message_id BINARY(16),
 category VARCHAR(60) NOT NULL, summary VARCHAR(1000) NOT NULL,
 ingress VARCHAR(100) NOT NULL, fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 body_sample VARCHAR(1400) NOT NULL,
 occurred_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 resolution VARCHAR(20) NOT NULL DEFAULT 'OPEN', replay_ref BINARY(16),
 FOREIGN KEY(job_id) REFERENCES work_job(id), FOREIGN KEY(message_id) REFERENCES outbox_message(id),
 INDEX(resolution,id), INDEX(job_id,occurred_at,id)
) ENGINE=InnoDB;
-- Explicit operator-supplied epoch: retries of the same recovery cannot starve a healthy backlog.
CREATE TABLE transport_recovery (
 epoch BINARY(16) NOT NULL, job_id BINARY(16) NOT NULL, message_id BINARY(16) NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(epoch,job_id), FOREIGN KEY(job_id) REFERENCES work_job(id),
 FOREIGN KEY(message_id) REFERENCES outbox_message(id)
) ENGINE=InnoDB;
