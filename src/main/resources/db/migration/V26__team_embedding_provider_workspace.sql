-- Native Beijing DashScope requests use a supplier business-space hostname, not a user URL.
-- Existing rows require an explicit administrator update; never guess an account identifier.
ALTER TABLE team_embedding_connection ADD COLUMN provider_workspace_id VARCHAR(63);
