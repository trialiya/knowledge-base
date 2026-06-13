CREATE TABLE phrase
(
    ID         BIGSERIAL
        PRIMARY KEY,
    CATEGORY   VARCHAR                                NOT NULL,
    LABEL      VARCHAR                                NOT NULL,
    TEXT       TEXT                                   NOT NULL,
    POSITION   INTEGER                  DEFAULT 0     NOT NULL,
    ENABLED    BOOLEAN                  DEFAULT TRUE  NOT NULL,
    FAVORITE   BOOLEAN                  DEFAULT FALSE NOT NULL,
    CREATED_AT TIMESTAMP WITH TIME ZONE DEFAULT NOW() NOT NULL,
    UPDATED_AT TIMESTAMP WITH TIME ZONE DEFAULT NOW() NOT NULL
);

CREATE INDEX ix_phrase_category_position
    ON phrase (category, position);

-- Seed: migrated from the former hardcoded GIT_PHRASES set.
-- Fixes applied vs. the old data:
--   * label typo "Просмотри изменений" -> "Просмотр изменений"
--   * its text used to be copied from "Поиск коммита по тексту" -> given its own text
--   * commented-out drafts are not carried over (disabled on purpose); seed any you want
--     to keep with enabled = false
-- Placeholders use {{...}} and are now literal text edited by hand (no i18next interpolation).
-- NOTE: texts are reconstructed from chat.gitPhrases.* — reconcile with your actual i18n values.
INSERT INTO phrase (category, label, text, position, enabled, favorite) VALUES
                                                                            ('Анализ',    'История коммитов',        'Покажи историю коммитов файла {{файл}} и объясни ключевые изменения', 0, TRUE, FALSE),
                                                                            ('Анализ',    'Поиск коммита по тексту', 'Найди коммит, в котором появился текст «{{текст}}», и покажи его diff', 1, TRUE, FALSE),
                                                                            ('Анализ',    'Просмотр изменений',      'Покажи изменения в файле {{файл}} между ревизиями {{от}} и {{до}}',     2, TRUE, FALSE),
                                                                            ('Коммиты',   'Имя коммита',             'Опиши незакоммиченные изменения и предложи имя коммита',                0, TRUE, FALSE),
                                                                            ('Документы', 'Черновик документа',      'Создай документ-черновик по теме «{{тема}}» с разделами',               0, TRUE, FALSE);