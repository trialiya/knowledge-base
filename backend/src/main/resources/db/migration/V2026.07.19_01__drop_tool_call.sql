-- tool_call больше не читается кодом с #121 (данные вызовов инструментов переехали в
-- chat_message.tool_data/meta.invocations, а точечный lookup теперь на tool_call_index) —
-- таблица не нужна.
DROP TABLE tool_call;
