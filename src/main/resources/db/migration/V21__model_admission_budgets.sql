-- Durable ceilings survive worker attempts. Waiting is charged before sleeping;
-- a crash can conservatively consume a slice, but cannot reset the allowance.
ALTER TABLE agent_run
  ADD COLUMN model_wait_millis BIGINT NOT NULL DEFAULT 0,
  ADD COLUMN model_retries INT NOT NULL DEFAULT 0,
  ADD CONSTRAINT ck_model_wait CHECK (model_wait_millis BETWEEN 0 AND 30000),
  ADD CONSTRAINT ck_model_retries CHECK (model_retries BETWEEN 0 AND 2);
