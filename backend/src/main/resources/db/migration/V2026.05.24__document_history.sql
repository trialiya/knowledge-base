-- ── 1. Optimistic locking column ─────────────────────────────────────────────
-- Spring Data JDBC maps @Version to this column automatically.
-- Existing rows start at version = 1 (non-null, consistent with entity default).
ALTER TABLE documents
    ADD COLUMN version integer NOT NULL DEFAULT 1;

-- ── 2. History table ──────────────────────────────────────────────────────────
-- Each row is an immutable snapshot written BEFORE the document is updated.
-- version matches the documents.version value at the time the snapshot was taken,
-- so versions in this table are always < the current documents.version.
--
--   documents.version = 4  →  history contains snapshots for versions 1, 2, 3
--
CREATE TABLE document_history
(
    id          bigserial PRIMARY KEY,
    document_id bigint                   NOT NULL
        REFERENCES documents (id) ON DELETE CASCADE,
    version     integer                  NOT NULL,
    title       text                     NOT NULL,
    type        text                     NOT NULL,
    description text,
    updated_at  timestamp with time zone NOT NULL,

    CONSTRAINT uq_document_history_version UNIQUE (document_id, version)
);

CREATE INDEX idx_document_history_document_id
    ON document_history (document_id, version DESC);

INSERT INTO document_history (document_id, version, title, type, description, updated_at)
SELECT id, 1, title, type, description, updated_at
FROM documents;
