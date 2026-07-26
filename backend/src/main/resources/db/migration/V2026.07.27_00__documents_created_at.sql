-- Adds documents.created_at so the knowledge-base "Info" panel can show a real
-- creation date. Until now the UI asked for `createdAt` and always got null:
-- the table only ever tracked updated_at.
--
-- Existing rows have no record of when they were created, so they are seeded
-- with updated_at — the closest known lower bound (never later than reality).
ALTER TABLE documents ADD COLUMN created_at TIMESTAMP WITH TIME ZONE;

UPDATE documents SET created_at = updated_at WHERE created_at IS NULL;

ALTER TABLE documents ALTER COLUMN created_at SET DEFAULT now();
ALTER TABLE documents ALTER COLUMN created_at SET NOT NULL;
