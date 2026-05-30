-- Drop the trigram index on description
--
-- Root cause: every description update was a non-HOT update because the GIN
-- trigram index on `description` prevents HOT (Heap-Only Tuple) updates.
-- Combined with the default autovacuum thresholds this caused dead-tuple
-- accumulation and TOAST bloat, making even PK lookups slow despite <50 rows.
--
-- Fix:
--  – aggressively tune autovacuum on documents and its TOAST table so
--    that dead tuples are cleaned before they accumulate.
--  – drop the index; at <50 rows a seq-scan on any text search is faster
--    than maintaining a large GIN structure, and HOT updates become
--    possible again for description changes.
--
-- The trigram index can be reintroduced on a dedicated `document_content`
-- table if the row count grows and full-text search latency becomes a concern.
ALTER TABLE documents SET (
    autovacuum_vacuum_scale_factor      = 0.0,
    autovacuum_vacuum_threshold         = 20,
    autovacuum_vacuum_cost_delay        = 0,    -- не троттлить, таблица крошечная
    autovacuum_analyze_scale_factor     = 0.0,
    autovacuum_analyze_threshold        = 20,

    toast.autovacuum_vacuum_scale_factor = 0.0,
    toast.autovacuum_vacuum_threshold    = 20,
    toast.autovacuum_vacuum_cost_delay   = 0
);

DROP INDEX documents_description__index;
