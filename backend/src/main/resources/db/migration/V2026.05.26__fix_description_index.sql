-- ── Fix: replace GiST index with GIN for description trigram search ──────────
-- GiST index on description with gist_trgm_ops fails with:
--   "index row requires 8360 bytes, maximum size is 8191"
-- when a document description is large enough that its trigram representation
-- exceeds the btree page limit (1/3 of 8 KB page).
-- GIN indexes handle large text values much better for trigram operations.

DROP INDEX IF EXISTS documents_description__index;

CREATE INDEX documents_description__index
    ON documents USING gin (description gin_trgm_ops);
