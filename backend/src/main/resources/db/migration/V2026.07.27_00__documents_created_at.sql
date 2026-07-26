-- Adds documents.created_at so the knowledge-base "Info" panel can show a real
-- creation date. Until now the UI asked for `createdAt` and always got null:
-- the table only ever tracked updated_at.
--
-- The earliest document_history snapshot carries an accurate creation timestamp:
-- DocumentService.create() writes one right after INSERT, and the V2026.05.24
-- backfill did the same for the rows that predate the history table. Either way
-- it predates every later edit, unlike documents.updated_at, which tracks the
-- *last* edit and would misreport creation time for any document touched since.
--
-- Take the MINIMUM available version rather than hardcoding version = 1: the
-- numbering is not stable across the schema's own history — V2026.06.01 rewrote
-- it in place (`SET version = version - 1 WHERE version > 1`), and nothing in
-- the schema guarantees a surviving row at exactly 1. The oldest snapshot is
-- the oldest snapshot whatever it happens to be numbered.
--
-- Fall back to updated_at for documents with no history at all (the system nodes
-- inserted straight by V2026.05.22 and never edited since).
ALTER TABLE documents ADD COLUMN created_at TIMESTAMP WITH TIME ZONE;

UPDATE documents d
SET created_at = COALESCE(
    (SELECT dh.updated_at
     FROM document_history dh
     WHERE dh.document_id = d.id
     ORDER BY dh.version
     LIMIT 1),
    d.updated_at);

ALTER TABLE documents ALTER COLUMN created_at SET DEFAULT now();
ALTER TABLE documents ALTER COLUMN created_at SET NOT NULL;
