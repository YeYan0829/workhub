-- Existing GitHub open mirrors use TRIAGE and require a local maintainer decision.
UPDATE task SET triage_required=TRUE WHERE status='TRIAGE';
