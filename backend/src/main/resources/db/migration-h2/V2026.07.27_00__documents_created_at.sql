-- Adds documents.created_at so the knowledge-base "Info" panel can show a real
-- creation date. Until now the UI asked for `createdAt` and always got null:
-- the table only ever tracked updated_at.
--
-- document_history.version=1 already carries an accurate creation timestamp:
-- DocumentService.create() writes that snapshot right after INSERT, and the
-- V2026.05.24 backfill did the same for pre-existing rows at the time. Either
-- way it predates every later edit, unlike documents.updated_at, which tracks
-- the *last* edit and would misreport creation time for any document touched
-- since. Fall back to updated_at only for the (should not happen) case of a
-- document with no version=1 snapshot at all.
ALTER TABLE documents ADD COLUMN created_at TIMESTAMP WITH TIME ZONE;

UPDATE documents d
SET created_at = COALESCE(
    (SELECT dh.updated_at
     FROM document_history dh
     WHERE dh.document_id = d.id AND dh.version = 1),
    d.updated_at)
WHERE created_at IS NULL;

ALTER TABLE documents ALTER COLUMN created_at SET DEFAULT now();
ALTER TABLE documents ALTER COLUMN created_at SET NOT NULL;
