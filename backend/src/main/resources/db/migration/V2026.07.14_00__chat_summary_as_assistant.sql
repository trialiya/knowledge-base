-- Summary messages produced by SummarizeService used to be stored as SYSTEM, but not all models
-- accept system messages in the middle of a conversation — switch them to ASSISTANT, matching the
-- earlier tool-call breadcrumbs migration (see V2026.06.23_00).
UPDATE chat_message
SET type = 'ASSISTANT'
WHERE type = 'SYSTEM'
  AND summary = true;
