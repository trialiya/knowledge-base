UPDATE document_history
SET version = version - 1
WHERE version > 1;
