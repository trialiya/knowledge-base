-- Протокольные данные tool-цикла (см. одноимённую миграцию для PostgreSQL).
ALTER TABLE chat_message ADD COLUMN tool_data VARCHAR;
