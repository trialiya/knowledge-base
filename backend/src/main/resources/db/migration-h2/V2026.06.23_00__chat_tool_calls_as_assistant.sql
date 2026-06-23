-- «Крошки» вызовов инструментов раньше сохранялись как SYSTEM, но не все модели принимают
-- системные сообщения в середине диалога — переводим их в ASSISTANT. Эти сообщения отличаются
-- от системных summary-сообщений наличием meta (summary-сообщения имеют meta IS NULL).
UPDATE chat_message
SET type = 'ASSISTANT'
WHERE type = 'SYSTEM'
  AND meta IS NOT NULL;
