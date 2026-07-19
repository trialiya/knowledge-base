-- Меняем FK tool_call_index → chat_message на ON DELETE CASCADE,
-- чтобы при удалении чата строки индекса чистились автоматически.
ALTER TABLE tool_call_index
    DROP CONSTRAINT tool_call_index_message_id_fkey,
    ADD CONSTRAINT tool_call_index_message_id_fkey
        FOREIGN KEY (message_id) REFERENCES chat_message (id)
            ON DELETE CASCADE;

ALTER TABLE tool_call_index
    DROP CONSTRAINT tool_call_index_response_message_id_fkey,
    ADD CONSTRAINT tool_call_index_response_message_id_fkey
        FOREIGN KEY (response_message_id) REFERENCES chat_message (id)
            ON DELETE CASCADE;
