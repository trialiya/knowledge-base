-- Бэкфилл флага toolCalls в JSON meta существующих «крошек» вызовов инструментов, чтобы код
-- не поддерживал старые записи без этого поля. meta в новом формате всегда начинается с
-- {"runId": (runId — первое поле; вложенные объекты инструментов начинаются с {"name"),
-- поэтому вставка по этому якорю затрагивает только внешний объект.
UPDATE chat_message
SET meta = REPLACE(meta, '{"runId":', '{"toolCalls":true,"runId":')
WHERE meta IS NOT NULL
  AND meta LIKE '{%'
  AND meta NOT LIKE '%"toolCalls"%';
