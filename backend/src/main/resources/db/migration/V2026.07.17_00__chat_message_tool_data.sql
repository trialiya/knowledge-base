-- Протокольные данные tool-цикла: для ASSISTANT — tool_calls (id/type/name/arguments),
-- для TOOL — responses (id/name/responseData). Заполняется при раздельном сохранении
-- сегментов ответа (ToolCallingAdvisor с отключённой внутренней историей); нужна для
-- полного восстановления диалога в формате OpenAI при следующих запросах к модели.
ALTER TABLE chat_message ADD COLUMN tool_data TEXT;
