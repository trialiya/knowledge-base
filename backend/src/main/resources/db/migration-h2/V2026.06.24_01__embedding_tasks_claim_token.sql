-- H2: just the column — partial indexes and SKIP LOCKED are never used in H2 mode.
ALTER TABLE embedding_tasks ADD COLUMN claim_token UUID;
