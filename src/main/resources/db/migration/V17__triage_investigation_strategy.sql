ALTER TABLE agent_run ADD COLUMN triage_strategy VARCHAR(16) NULL,
  ADD CONSTRAINT chk_triage_strategy CHECK (triage_strategy IS NULL OR triage_strategy IN ('WORKFLOW','GUIDED','AGENT'));
