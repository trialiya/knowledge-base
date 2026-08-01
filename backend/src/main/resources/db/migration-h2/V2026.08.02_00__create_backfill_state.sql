-- Одноразовые фоновые бэкфиллы (run-once): строка появляется после успешного выполнения
-- соответствующего бэкфилла; наличие строки означает «уже сделано, при старте не повторять».
-- Заполняется кодом (например, ChatMemoryService#backfillToolCallIdsIfNeeded) в той же
-- транзакции, что и сам бэкфилл, поэтому повторные старты приложения — дешёвый no-op.
CREATE TABLE backfill_state (
    name     VARCHAR(255) PRIMARY KEY,
    done_at  TIMESTAMP NOT NULL
);
