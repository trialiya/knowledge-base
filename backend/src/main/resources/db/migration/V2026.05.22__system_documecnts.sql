truncate table document_embeddings cascade;

truncate table documents cascade;

INSERT INTO documents (id, title, type, parent_id, updated_at, description, position, is_system) VALUES (1, 'Проект', 'folder', null, current_timestamp, e'# Общая информация
', 0, true);
INSERT INTO documents (id, title, type, parent_id, updated_at, description, position, is_system) VALUES (2, 'Введение', 'document', 1, current_timestamp, e'# Общие сведения
', 0, true);
