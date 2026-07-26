-- Splits chat_topic.topic into user_topic (set explicitly via PUT .../topic) and
-- ai_topic (set by the recordChatInsights tool call), replacing the is_user flag
-- that previously made the two mutually exclusive.
ALTER TABLE chat_topic ADD COLUMN user_topic VARCHAR(255);
ALTER TABLE chat_topic ADD COLUMN ai_topic VARCHAR(255);

UPDATE chat_topic SET user_topic = topic WHERE is_user = true;
UPDATE chat_topic SET ai_topic = topic WHERE is_user = false;

ALTER TABLE chat_topic DROP COLUMN topic;
ALTER TABLE chat_topic DROP COLUMN is_user;
