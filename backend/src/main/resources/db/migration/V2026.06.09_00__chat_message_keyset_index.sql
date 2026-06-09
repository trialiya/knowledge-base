CREATE INDEX IF NOT EXISTS ix_chat_message_keyset
    ON chat_message (conversation_id, summary, created_at DESC, id DESC);
