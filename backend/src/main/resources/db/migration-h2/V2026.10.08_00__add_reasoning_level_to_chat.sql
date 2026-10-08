-- Уровень рассуждений, выбранный в чате (id из kb.chat.*.reasoning.levels). chat_topic — выбор
-- чата, как model и mode; chat_pending_message — снимок выбора у сообщения в очереди, как его
-- model/mode/project. Хранится id как выбран: что он отправляет провайдеру, решает конфигурация
-- модели на момент прогона (ReasoningOptions).
ALTER TABLE chat_topic ADD COLUMN reasoning VARCHAR(64);
ALTER TABLE chat_pending_message ADD COLUMN reasoning VARCHAR(64);
