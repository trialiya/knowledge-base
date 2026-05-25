-- ── Document summary support ──────────────────────────────────────────────────
--
-- summary                — AI-generated summary, populated manually via
--                          POST /api/documents/{id}/summarize.
--                          NULL means "never summarised".
--
-- summary_source_version — the value of description_version at the time the
--                          summary was last generated.
--                          NULL while summary IS NULL.
--                          stale = (summary_source_version < description_version)
--
-- description_version    — incremented ONLY when the description column changes.
--                          Deliberately separate from the @Version column, which
--                          also grows on rename / move / reorder and must not
--                          affect the stale calculation.
--                          All existing rows start at 1 (consistent with the
--                          entity field default).

ALTER TABLE documents
    ADD COLUMN summary                text,
    ADD COLUMN summary_source_version integer,
    ADD COLUMN description_version    integer NOT NULL DEFAULT 1;

update documents
set description_version = version;

ALTER TABLE document_history
    ADD COLUMN summary                text,
    ADD COLUMN summary_source_version integer,
    ADD COLUMN description_version    integer NOT NULL DEFAULT 1;

update document_history
set description_version = version;
