-- Existing opt-ins start a new conservative cutoff; never infer historical consent.
ALTER TABLE project_triage_settings ADD enabled_since DATETIME(6) NULL;
UPDATE project_triage_settings SET enabled_since=UTC_TIMESTAMP(6) WHERE enabled=true;
ALTER TABLE github_issue ADD discovery_mode VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN',
 ADD CHECK(discovery_mode IN ('UNKNOWN','BACKFILL','LIVE','RECONCILE'));
-- A disable/re-enable cannot resurrect an execution that was already stopped.
ALTER TABLE agent_run ADD automatic_stop_requested BOOLEAN NOT NULL DEFAULT FALSE;
