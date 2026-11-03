-- Project-owned triage discovery, with stable keyset pagination independent of the initiating user.
CREATE INDEX ix_agent_run_triage_history ON agent_run(project_id,triage_task_id,created_at,id);
