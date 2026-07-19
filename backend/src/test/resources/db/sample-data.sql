-- H2 sample/test dataset — see CLAUDE.md ('Тестовые данные для H2') for a pointer
-- to this file and to SampleDataFixtureTest, the worked usage example. Full
-- rationale lives here, next to the data it describes:
--
-- Schema target: db/migration-h2 (H2 in PostgreSQL mode). NOT valid against the
-- real db/migration (Postgres) schema as-is (vector/array column types differ).
--
-- Contents:
--  * chat_topic + chat_message (16 rows) — a real captured conversation asking the
--    AI to summarize backend/build.gradle's commit history and create documents for
--    it (documents 75/76 below). Exercises USER/ASSISTANT/TOOL message types, the
--    meta column (ChatMessageMeta: runId/toolCalls/invocations) and the tool_data
--    column (ToolData: tool call args / tool responses) with realistic JSON. Large
--    repeated tool-response JSON (multi-KB diffs, full tree listings) was trimmed to
--    representative examples — shape preserved, not byte-exact.
--  * tool_call_index (6 rows) — the callId -> chat_message id index (see
--    ChatMemoryService#findToolCallDetail); rows 500-505 link the captured
--    getCommitLog/getCommitDiff/getTreeSkeleton/createDocument calls above to
--    their ASSISTANT/TOOL row ids.
--  * documents/document_history — folder 75 ('анализ') and document 76 (the
--    AI-authored chronology, full markdown content) from the captured conversation,
--    plus a hand-added document 77 exercising file links (/files?path=...), doc
--    links (/?doc=76), a doc link with an anchor (/?doc=76#...) and an external
--    link — the cases DocLinkTooltip/ChatDocLink branch on.
--  * attachments — one hand-added chat attachment (owner_type='chat', a build error
--    log) and one document attachment (owner_type='document', on doc 77), covering
--    both FK branches of the chk_attachment_owner constraint.
--  * embedding_tasks — one hand-added 'pending' row for document 77.
--
-- All explicit IDs are followed by ALTER TABLE ... ALTER COLUMN id RESTART WITH n,
-- so inserting further rows afterwards (in a test or via the app) won't collide.
--
-- Manual/local QA: point the H2 file-based profile at it and run the statements
-- once via the H2 console (enabled in application-h2.yaml,
-- http://localhost:8080/h2-console, JDBC URL jdbc:h2:./local-db/h2;MODE=PostgreSQL)
-- or org.h2.tools.RunScript. Plain SQL, not templated/generated at build time —
-- extend it in place; keep new large text blobs trimmed to what the test actually
-- needs, matching the existing rows.

-- ── chat_topic ────────────────────────────────────────────────────────────
INSERT INTO chat_topic (conversation_id, "user", is_user, topic, model, created_at, updated_at) VALUES
    ('c5dfa618-0ad2-4845-a976-ada46c50f9a4', 'admin', FALSE, 'История коммитов backend/build.gradle', NULL, '2026-07-18 20:59:02.915088', '2026-07-18 21:01:19.071770');

-- ── chat_message (captured conversation, position 1..16) ────────────────────
INSERT INTO chat_message (id, conversation_id, content, created_at, position, summarized, summary, type, meta, tool_data) VALUES
    (1638, 'c5dfa618-0ad2-4845-a976-ada46c50f9a4', 'Покажи историю коммитов файла backend/build.gradle и объясни ключевые изменения', '2026-07-18 20:59:02.915088', 1, FALSE, FALSE, 'USER', NULL, NULL),
    (1639, 'c5dfa618-0ad2-4845-a976-ada46c50f9a4', '', '2026-07-18 20:59:06.671883', 2, FALSE, FALSE, 'ASSISTANT', '{"runId":"3035ea20-df41-463a-95ac-de9b20f82af5","toolCalls":false,"invocations":[{"name":"getCommitLog","arguments":{"filePath":"backend/build.gradle","maxCount":"30"},"status":"OK","error":null,"resultMeta":{"items":[{"changesFilesCount":0,"shortHash":"8547d56","email":"41265764+trialiya@users.noreply.github.com","date":"2026-07-13T02:41:21+03:00","author":"trialiya","message":"Replace git subprocess calls with JGit for in-process operations (#79)"}]},"hasDetails":true,"callIndex":1,"resultGist":"size=17 (first 5)\n8547d56 2026-07-13 trialiya: Replace git subprocess calls with JGit for in-process operations (#79)"}]}', '{"toolCalls":[{"id":"call_00_ZxiPQMSGCql0DsAMTc4M6297","type":"function","name":"recordChatInsights","arguments":"{\"topic\": \"история backend build.gradle\"}"},{"id":"call_01_obJfkGBZNNbS6dGMq5ds4538","type":"function","name":"getCommitLog","arguments":"{\"filePath\": \"backend/build.gradle\", \"maxCount\": 30}"}],"responses":null}'),
    (1640, 'c5dfa618-0ad2-4845-a976-ada46c50f9a4', '', '2026-07-18 20:59:07.020415', 3, FALSE, FALSE, 'TOOL', NULL, '{"toolCalls":null,"responses":[{"id":"call_00_ZxiPQMSGCql0DsAMTc4M6297","name":"recordChatInsights","responseData":"\"Done\""},{"id":"call_01_obJfkGBZNNbS6dGMq5ds4538","name":"getCommitLog","responseData":"[{\"hash\":\"8547d567e4c524805f74b0a523be2a8ec3892c1e\",\"shortHash\":\"8547d56\",\"author\":\"trialiya\",\"email\":\"41265764+trialiya@users.noreply.github.com\",\"date\":\"2026-07-13T02:41:21+03:00\",\"message\":\"Replace git subprocess calls with JGit for in-process operations (#79)\",\"files\":null},{\"hash\":\"ccb7fe3f7259b680d3094859ff379b6a325232c5\",\"shortHash\":\"ccb7fe3\",\"author\":\"trialiya\",\"email\":\"41265764+trialiya@users.noreply.github.com\",\"date\":\"2026-07-12T17:00:05+03:00\",\"message\":\"Improve web sandbox build & test guidance; fix frontend JSX formatting (#76)\",\"files\":null},{\"hash\":\"10aaf73d664daed37441203b04ef91e0208186cd\",\"shortHash\":\"10aaf73\",\"author\":\"trialiya\",\"email\":\"41265764+trialiya@users.noreply.github.com\",\"date\":\"2026-06-22T00:35:12+03:00\",\"message\":\"Upgrade to Spring Boot 4.1 and Spring AI 2.0 (#50)\",\"files\":null}]"}]}'),
    (1641, 'c5dfa618-0ad2-4845-a976-ada46c50f9a4', 'Теперь запрошу diff для первых 9 коммитов:', '2026-07-18 20:59:13.150429', 4, FALSE, FALSE, 'ASSISTANT', '{"runId":"3035ea20-df41-463a-95ac-de9b20f82af5","toolCalls":false,"invocations":[{"name":"getCommitDiff","arguments":{"filePath":"backend/build.gradle","includePatch":"true","commitHashes":"33195a2,f3a158d,20a0562,3bf86e2,54f403d,1d73bdf,10aaf73,ccb7fe3,8547d56"},"status":"OK","error":null,"resultMeta":{"items":[{"changesFilesCount":1,"shortHash":"33195a2","email":"trialiya@gmail.com","date":"2026-05-20T01:07:42+03:00","author":"Trialiya","message":"Init frontend/backend"}]},"hasDetails":true,"callIndex":2,"resultGist":"size=9 (first 5)\n33195a2 2026-05-20 Trialiya: Init frontend/backend (1 files +79 -0)"}]}', '{"toolCalls":[{"id":"call_00_NksUXPjSRp5j2MDcJryA0234","type":"function","name":"getCommitDiff","arguments":"{\"commitHashes\": \"33195a2,f3a158d,20a0562,3bf86e2,54f403d,1d73bdf,10aaf73,ccb7fe3,8547d56\", \"filePath\": \"backend/build.gradle\", \"includePatch\": true}"}],"responses":null}'),
    (1642, 'c5dfa618-0ad2-4845-a976-ada46c50f9a4', '', '2026-07-18 20:59:13.246145', 5, FALSE, FALSE, 'TOOL', NULL, '{"toolCalls":null,"responses":[{"id":"call_00_NksUXPjSRp5j2MDcJryA0234","name":"getCommitDiff","responseData":"[{\"hash\":\"33195a26f15d8098681654ba5a51134990b40fda\",\"shortHash\":\"33195a2\",\"author\":\"Trialiya\",\"email\":\"trialiya@gmail.com\",\"date\":\"2026-05-20T01:07:42+03:00\",\"message\":\"Init frontend/backend\",\"files\":[{\"status\":\"A\",\"path\":\"backend/build.gradle\",\"oldPath\":null,\"additions\":79,\"deletions\":0,\"patch\":\"diff --git a/backend/build.gradle b/backend/build.gradle\\nnew file mode 100644\\nindex 0000000..504e588\\n--- /dev/null\\n+++ b/backend/build.gradle\\n@@ -0,0 +1,79 @@\\n+plugins {\\n+    id ''java''\\n+    id ''org.springframework.boot'' version ''3.5.7''\\n+}\\n\"}]}]"}]}'),
    (1643, 'c5dfa618-0ad2-4845-a976-ada46c50f9a4', 'Теперь запрошу diff для ключевых коммитов, чтобы понять суть изменений:', '2026-07-18 20:59:13.160429', 6, FALSE, FALSE, 'ASSISTANT', '{"runId":"3035ea20-df41-463a-95ac-de9b20f82af5","toolCalls":false,"invocations":[{"name":"getCommitDiff","arguments":{"filePath":"backend/build.gradle","includePatch":"true","commitHashes":"38e5ba2,af2f81b,5998209,18c9b31,166b0a2,dfed77c,359892c,5cb08b9"},"status":"OK","error":null,"resultMeta":{"items":[{"changesFilesCount":1,"shortHash":"38e5ba2","email":"49699333+dependabot[bot]@users.noreply.github.com","date":"2026-06-17T23:58:42+03:00","author":"dependabot[bot]","message":"build(deps): bump org.springframework.boot from 3.5.14 to 3.5.15 in the minor-and-patch group (#24)"}]},"hasDetails":true,"callIndex":3,"resultGist":"size=8 (first 5)\n38e5ba2 2026-06-17 dependabot[bot]: build(deps): bump org.springframework.boot from 3.5.14 to 3.5.15 in the minor-and-patch group (#24) (1 files +1 -1)"}]}', '{"toolCalls":[{"id":"call_00_FTMh2npDo459NqP8edYu6973","type":"function","name":"getCommitDiff","arguments":"{\"commitHashes\": \"38e5ba2,af2f81b,5998209,18c9b31,166b0a2,dfed77c,359892c,5cb08b9\", \"filePath\": \"backend/build.gradle\", \"includePatch\": true}"}],"responses":null}'),
    (1644, 'c5dfa618-0ad2-4845-a976-ada46c50f9a4', '', '2026-07-18 20:59:17.341412', 7, FALSE, FALSE, 'TOOL', NULL, '{"toolCalls":null,"responses":[{"id":"call_00_FTMh2npDo459NqP8edYu6973","name":"getCommitDiff","responseData":"[{\"hash\":\"38e5ba2c6941bf43815588d2dbbdb1d5be9590ce\",\"shortHash\":\"38e5ba2\",\"author\":\"dependabot[bot]\",\"email\":\"49699333+dependabot[bot]@users.noreply.github.com\",\"date\":\"2026-06-17T23:58:42+03:00\",\"message\":\"build(deps): bump org.springframework.boot from 3.5.14 to 3.5.15 in the minor-and-patch group (#24)\",\"files\":[{\"status\":\"M\",\"path\":\"backend/build.gradle\",\"oldPath\":null,\"additions\":1,\"deletions\":1,\"patch\":\"diff --git a/backend/build.gradle b/backend/build.gradle\\nindex 82e3860..0b749e9 100644\\n--- a/backend/build.gradle\\n+++ b/backend/build.gradle\\n@@ -1,6 +1,6 @@\\n plugins {\\n     id ''java''\\n-    id ''org.springframework.boot'' version ''3.5.14''\\n+    id ''org.springframework.boot'' version ''3.5.15''\\n     id ''io.spring.dependency-management'' version ''1.1.7''\\n     id ''com.diffplug.spotless''\\n }\\n\"}]}]"}]}'),
    (1645, 'c5dfa618-0ad2-4845-a976-ada46c50f9a4', '## История коммитов [`backend/build.gradle`](/files?path=backend/build.gradle)

Всего **17 коммитов** за период 20.05–13.07.2026. Вот ключевые изменения в хронологическом порядке:

---

### 🔹 **Начальный сетап** (`33195a2` — 20.05)
Создание файла: Spring Boot 3.5.7, Java 25, Spring AI 1.1.5, PostgreSQL, Flexmark (Markdown), Guava, Lombok, Spotless.

---

### 🔹 **Семантический поиск** (`f3a158d` — 20.05)
Spring AI 1.1.5 → **1.1.6**.

### 🔹 **Flyway-миграции** (`20a0562` — 21.05)
Добавлены `flyway-core` + `flyway-database-postgresql`. Удалены Flexmark-зависимости (больше не нужны).

### 🔹 **Интеграция фронтенда** (`3bf86e2` — 21.05)
`bootJar` теперь зависит от сборки фронтенда (`:frontend:copyFrontendBuild`).

### 🔹 **Docker + Actuator** (`54f403d` — 22.05)
Добавлен `spring-boot-starter-actuator`. Убран `netty-resolver-dns-native-macos`. Задача фронтенда переименована: `copyFrontendBuild` → `copyFrontend`.

### 🔹 **H2 для standalone** (`359892c` — 28.05)
Добавлена `com.h2database:h2` — встроенная БД для профиля без PostgreSQL и семантического поиска.

### 🔹 **Tree-sitter для file outline** (`5998209` — 25.05)
Добавлены 5 tree-sitter зависимостей: ядро + парсеры Java, JS, TS, Python.

### 🔹 **Testcontainers** (`1d73bdf` — 14.06)
Добавлены `testcontainers:postgresql` + `testcontainers:junit-jupiter` для интеграционных тестов с PostgreSQL.

### 🔹 **HTTP Basic Auth** (`5cb08b9` — 20.06)
Добавлены `spring-boot-starter-security` + `spring-security-test`.

### 🔹 **⚠️ Spring Boot 4.1 + Spring AI 2.0** (`10aaf73` — 22.06)
**Самое крупное обновление:**
- Spring Boot 3.5.15 → **4.1.0**, Spring AI 1.1.6 → **2.0.0**
- Добавлен `spring-boot-flyway` — в SB4 `FlywayAutoConfiguration` вынесен из автоконфигурации в отдельный модуль
- Добавлен `jackson-datatype-jsr310` — SB4 перешёл на Jackson 3, а внутренняя сериализация всё ещё использует Jackson 2
- Добавлены `spring-boot-data-jdbc-test` + `spring-boot-jdbc-test` — тестовые «слайсы» тоже вынесены из автоконфигурации
- Добавлен `testcontainers-bom:1.21.4` — SB4 больше не управляет версиями Testcontainers

### 🔹 **Фиксы сборки** (`ccb7fe3` — 12.07)
- Обычный `jar` тоже зависит от `:frontend:copyFrontend` (иначе валидация implicit dependency падает)
- `compileTestJava` и `test` → `mustRunAfter :frontend:copyFrontend`

### 🔹 **JGit** (`8547d56` — 13.07)
Добавлен `org.eclipse.jgit:7.7.0` — замена git subprocess для операций с репозиторием (кроме `git grep`).

---

### Dependabot-обновления (между основными):
| Коммит | Что |
|---|---|
| `166b0a2` | Spotless 6.25.0 → управление через BOM (версия убрана) |
| `18c9b31` | Guava 33.5.0 → 33.6.0 |
| `af2f81b` | Spring Boot 3.5.7 → 3.5.14, tree-sitter-js 0.23.1 → 0.25.0 |
| `38e5ba2` | Spring Boot 3.5.14 → 3.5.15 |

---

**Главная веха** — переход на Spring Boot 4.1 / Spring AI 2.0 (`10aaf73`): он принёс больше всего изменений из-за ломающих изменений в экосистеме Spring (Jackson 3, вынесенные модули, убранный Testcontainers BOM).', '2026-07-18 20:59:39.446856', 8, FALSE, FALSE, 'ASSISTANT', NULL, NULL),
    (1646, 'c5dfa618-0ad2-4845-a976-ada46c50f9a4', 'создай папку "анализ" и документ с хронологией изменений файла', '2026-07-18 21:00:48.764723', 9, FALSE, FALSE, 'USER', NULL, NULL),
    (1647, 'c5dfa618-0ad2-4845-a976-ada46c50f9a4', '', '2026-07-18 21:00:52.356742', 10, FALSE, FALSE, 'ASSISTANT', '{"runId":"e3f2e167-ff17-46c3-9cd2-896a1be2017b","toolCalls":false,"invocations":[{"name":"getTreeSkeleton","arguments":{},"status":"OK","error":null,"hasDetails":true,"callIndex":1,"resultGist":"size=39 (first 5)\ndoc:1 title=Проект type=folder sys=1\ndoc:7 title=\"Модели данных\" type=folder parent=1\ndoc:40 title=\"База знаний — структура и API\" type=document parent=35\ndoc:22 title=\"Поиск — руководство пользователя\" type=document parent=16\ndoc:42 title=\"История описания документа\" type=document parent=16"}]}', '{"toolCalls":[{"id":"call_00_hsMmPmAI1ScWq7TQwCMr8527","type":"function","name":"recordChatInsights","arguments":"{\"topic\": \"создание папки анализ\"}"},{"id":"call_01_0DvT7d12yVkJs6134bZK5223","type":"function","name":"getTreeSkeleton","arguments":"{}"}],"responses":null}'),
    (1648, 'c5dfa618-0ad2-4845-a976-ada46c50f9a4', '', '2026-07-18 21:00:52.426159', 11, FALSE, FALSE, 'TOOL', NULL, '{"toolCalls":null,"responses":[{"id":"call_00_hsMmPmAI1ScWq7TQwCMr8527","name":"recordChatInsights","responseData":"\"Done\""},{"id":"call_01_0DvT7d12yVkJs6134bZK5223","name":"getTreeSkeleton","responseData":"[{\"id\":1,\"title\":\"Проект\",\"type\":\"folder\",\"parentId\":null,\"version\":1,\"description\":null,\"descriptionVersion\":1,\"updatedAt\":null,\"children\":[],\"hasChildren\":true,\"system\":true,\"summary\":null,\"summaryStale\":false,\"summarySourceVersion\":null},{\"id\":7,\"title\":\"Модели данных\",\"type\":\"folder\",\"parentId\":1,\"version\":6,\"description\":null,\"descriptionVersion\":5,\"updatedAt\":null,\"children\":[],\"hasChildren\":true,\"system\":false,\"summary\":null,\"summaryStale\":false,\"summarySourceVersion\":null},{\"id\":40,\"title\":\"База знаний — структура и API\",\"type\":\"document\",\"parentId\":35,\"version\":4,\"description\":null,\"descriptionVersion\":3,\"updatedAt\":null,\"children\":[],\"hasChildren\":false,\"system\":false,\"summary\":null,\"summaryStale\":false,\"summarySourceVersion\":null},{\"id\":22,\"title\":\"Поиск — руководство пользователя\",\"type\":\"document\",\"parentId\":16,\"version\":6,\"description\":null,\"descriptionVersion\":4,\"updatedAt\":null,\"children\":[],\"hasChildren\":false,\"system\":false,\"summary\":null,\"summaryStale\":false,\"summarySourceVersion\":null},{\"id\":42,\"title\":\"История описания документа\",\"type\":\"document\",\"parentId\":16,\"version\":1,\"description\":null,\"descriptionVersion\":1,\"updatedAt\":null,\"children\":[],\"hasChildren\":false,\"system\":false,\"summary\":null,\"summaryStale\":false,\"summarySourceVersion\":null}]"}]}'),
    (1649, 'c5dfa618-0ad2-4845-a976-ada46c50f9a4', 'Создаю папку «анализ» в корне базы знаний:', '2026-07-18 21:00:55.839152', 12, FALSE, FALSE, 'ASSISTANT', '{"runId":"e3f2e167-ff17-46c3-9cd2-896a1be2017b","toolCalls":false,"invocations":[{"name":"createDocument","arguments":{"title":"анализ","type":"folder"},"status":"OK","error":null,"resultMeta":{"parent":null,"descriptionVersion":1,"id":75,"type":"folder","title":"анализ","version":1,"updated":"2026-07-18T21:00:55.850512"},"hasDetails":true,"callIndex":2,"resultGist":"doc:75 title=анализ type=folder version=1 updated=2026-07-18T21:00:55.850512"}]}', '{"toolCalls":[{"id":"call_00_P0jBk6jl0P6VTuGpfGDp1663","type":"function","name":"createDocument","arguments":"{\"title\": \"анализ\", \"type\": \"folder\", \"parentId\": null}"}],"responses":null}'),
    (1650, 'c5dfa618-0ad2-4845-a976-ada46c50f9a4', '', '2026-07-18 21:00:55.875680', 13, FALSE, FALSE, 'TOOL', NULL, '{"toolCalls":null,"responses":[{"id":"call_00_P0jBk6jl0P6VTuGpfGDp1663","name":"createDocument","responseData":"{\"id\":75,\"title\":\"анализ\",\"type\":\"folder\",\"parentId\":null,\"version\":1,\"descriptionVersion\":1,\"updatedAt\":\"2026-07-18T21:00:55.850512\",\"summaryStale\":false,\"summarySourceVersion\":null}"}]}'),
    (1651, 'c5dfa618-0ad2-4845-a976-ada46c50f9a4', 'Теперь создаю документ с хронологией:', '2026-07-18 21:01:16.424198', 14, FALSE, FALSE, 'ASSISTANT', '{"runId":"e3f2e167-ff17-46c3-9cd2-896a1be2017b","toolCalls":false,"invocations":[{"name":"createDocument","arguments":{"description":"# Хронология изменений [`backend/build.gradle`](/files?path=backend/build.gradle)\n\nВсего **17 коммит…(+4521)","title":"Хронология изменений backend/build.gradle","type":"document","parentId":"75"},"status":"OK","error":null,"resultMeta":{"parent":75,"descriptionVersion":1,"id":76,"type":"document","title":"Хронология изменений backend/build.gradle","version":1,"updated":"2026-07-18T21:01:16.433971"},"hasDetails":true,"callIndex":3,"resultGist":"doc:76 title=\"Хронология изменений backend/build.gradle\" type=document parent=75 version=1 updated=2026-07-18T21:01:16.433971"}]}', '{"toolCalls":[{"id":"call_00_b8FfPSBSjIgVT8Mfzbt20523","type":"function","name":"createDocument","arguments":"{\"title\": \"Хронология изменений backend/build.gradle\", \"type\": \"document\", \"parentId\": 75, \"description\": \"# Хронология изменений [`backend/build.gradle`](/files?path=backend/build.gradle)\\n\\nВсего **17 коммитов** за период 20.05–13.07.2026.\\n\\n---\\n\\n## 1. Начальный сетап\\n**`33195a2`** — 20.05.2026 — *Init frontend/backend*\\n\\nСоздание файла с нуля (79 строк):\\n- **Spring Boot 3.5.7**, Java 25\\n- **Spring AI 1.1.5** (OpenAI + chat memory JDBC)\\n- PostgreSQL (`org.postgresql:postgresql:42.7.11`)\\n- Flexmark (Markdown-парсинг)\\n- Guava 33.5.0, JSpecify, Lombok\\n- Spotless 6.25.0 (Google Java Format AOSP)\\n\\n---\\n\\n## 2. Семантический поиск\\n**`f3a158d`** — 20.05.2026 — *Semantic search*\\n\\n- Spring AI 1.1.5 → **1.1.6**\\n\\n---\\n\\n## 3. Flyway-миграции и системные документы\\n**`20a0562`** — 21.05.2026 — *feat: add system-protected documents and Flyway migrations*\\n\\n- ➕ `org.flywaydb:flyway-core`\\n- ➕ `org.flywaydb:flyway-database-postgresql`\\n- ➖ Удалены Flexmark-зависимости (больше не нужны)\\n\\n---\\n\\n## 4. Интеграция фронтенда в bootJar\\n**`3bf86e2`** — 21.05.2026 — *build: integrate frontend build into backend bootJar*\\n\\n- `bootJar` теперь зависит от `:frontend:copyFrontendBuild`\\n\\n---\\n\\n## 5. Docker, Actuator, внешняя конфигурация БД\\n**`54f403d`** — 22.05.2026 — *build: add Docker setup, externalize DB config, add Actuator, refine frontend build*\\n\\n- ➕ `spring-boot-starter-actuator`\\n- ➖ Убран `netty-resolver-dns-native-macos`\\n- Задача фронтенда переименована: `copyFrontendBuild` → `copyFrontend`\\n\\n---\\n\\n## 6. Рефакторинг зависимостей\\n**`dfed77c`** — 22.05.2026 — *refactor*\\n\\n- Версия PostgreSQL убрана (управляется BOM)\\n- Стиль кавычек унифицирован (скобки `()` убраны)\\n\\n---\\n\\n## 7. Dependabot: Spotless\\n**`166b0a2`** — 23.05.2026 — *bump com.diffplug.spotless from 6.25.0 to 8.5.1*\\n\\n- Spotless: версия убрана (теперь через BOM)\\n\\n---\\n\\n## 8. Dependabot: Guava\\n**`18c9b31`** — 23.05.2026 — *bump the minor-and-patch group with 2 updates*\\n\\n- Guava 33.5.0 → **33.6.0**\\n\\n---\\n\\n## 9. Tree-sitter для file outline\\n**`5998209`** — 25.05.2026 — *feature: add file outline tool with tree-sitter and regex fallback*\\n\\n- ➕ `io.github.bonede:tree-sitter:0.26.6`\\n- ➕ `tree-sitter-java:0.23.5`\\n- ➕ `tree-sitter-javascript:0.23.1`\\n- ➕ `tree-sitter-typescript:0.23.2`\\n- ➕ `tree-sitter-python:0.25.0`\\n- Стиль кавычек унифицирован (двойные → одинарные)\\n\\n---\\n\\n## 10. Dependabot: Spring Boot + tree-sitter-js\\n**`af2f81b`** — 25.05.2026 — *bump the minor-and-patch group with 2 updates*\\n\\n- Spring Boot 3.5.7 → **3.5.14**\\n- tree-sitter-javascript 0.23.1 → **0.25.0**\\n\\n---\\n\\n## 11. H2 для standalone-развёртывания\\n**`359892c`** — 28.05.2026 — *feature: add embedded H2 profile for standalone deployment*\\n\\n- ➕ `com.h2database:h2`\\n\\n---\\n\\n## 12. Testcontainers для интеграционных тестов\\n**`1d73bdf`** — 14.06.2026 — *test(backend): add Testcontainers PostgreSQL integration tests*\\n\\n- ➕ `org.testcontainers:postgresql`\\n- ➕ `org.testcontainers:junit-jupiter`\\n\\n---\\n\\n## 13. Dependabot: Spring Boot patch\\n**`38e5ba2`** — 17.06.2026 — *bump org.springframework.boot from 3.5.14 to 3.5.15*\\n\\n- Spring Boot 3.5.14 → **3.5.15**\\n\\n---\\n\\n## 14. HTTP Basic Auth\\n**`5cb08b9`** — 20.06.2026 — *Add HTTP Basic auth with single in-memory user from config*\\n\\n- ➕ `spring-boot-starter-security`\\n- ➕ `spring-security-test`\\n\\n---\\n\\n## 15. ⚠️ **КЛЮЧЕВОЕ:** Spring Boot 4.1 + Spring AI 2.0\\n**`10aaf73`** — 22.06.2026 — *Upgrade to Spring Boot 4.1 and Spring AI 2.0 (#50)*\\n\\nСамое масштабное обновление (18 строк добавлено, 2 удалено):\\n\\n- **Spring Boot 3.5.15 → 4.1.0**\\n- **Spring AI 1.1.6 → 2.0.0**\\n- ➕ `spring-boot-flyway` — в SB4 `FlywayAutoConfiguration` вынесен в отдельный модуль\\n- ➕ `jackson-datatype-jsr310` — SB4 перешёл на Jackson 3, но внутренняя сериализация (ChatMemoryService, Spring Data JDBC, tool-call persistence) всё ещё требует Jackson 2\\n- ➕ `spring-boot-data-jdbc-test` + `spring-boot-jdbc-test` — тестовые слайсы вынесены из автоконфигурации\\n- ➕ `testcontainers-bom:1.21.4` — SB4 больше не управляет версиями Testcontainers\\n\\n---\\n\\n## 16. Фиксы сборки фронтенда\\n**`ccb7fe3`** — 12.07.2026 — *Improve web sandbox build & test guidance (#76)*\\n\\n- Обычный `jar` теперь тоже зависит от `:frontend:copyFrontend` (валидация implicit dependency)\\n- `compileTestJava` и `test` → `mustRunAfter :frontend:copyFrontend`\\n\\n---\\n\\n## 17. JGit вместо git subprocess\\n**`8547d56`** — 13.07.2026 — *Replace git subprocess calls with JGit (#79)*\\n\\n- ➕ `org.eclipse.jgit:7.7.0` — in-process Git для GitService (кроме `git grep`)\\n\\n---\\n\\n## Сводка по типам изменений\\n\\n| Тип | Количество |\\n|---|---|\\n| Новые зависимости | 16 |\\n| Обновления версий (dependabot) | 4 |\\n| Удаление зависимостей | 3 (flexmark × 3) |\\n| Мажорные миграции | 1 (SB4.1 + SA2.0) |\\n| Инфраструктура сборки | 4 (bootJar, jar, test ordering) |\\n\"}"}],"responses":null}'),
    (1652, 'c5dfa618-0ad2-4845-a976-ada46c50f9a4', '', '2026-07-18 21:01:16.454361', 15, FALSE, FALSE, 'TOOL', NULL, '{"toolCalls":null,"responses":[{"id":"call_00_b8FfPSBSjIgVT8Mfzbt20523","name":"createDocument","responseData":"{\"id\":76,\"title\":\"Хронология изменений backend/build.gradle\",\"type\":\"document\",\"parentId\":75,\"version\":1,\"descriptionVersion\":1,\"updatedAt\":\"2026-07-18T21:01:16.433971\",\"summaryStale\":false,\"summarySourceVersion\":null}"}]}'),
    (1653, 'c5dfa618-0ad2-4845-a976-ada46c50f9a4', 'Готово:

- 📁 Папка [анализ](/?doc=75) — создана в корне базы знаний
- 📄 Документ [Хронология изменений backend/build.gradle](/?doc=76) — внутри папки «анализ», содержит все 17 коммитов с описанием ключевых изменений, от начального сетапа до последнего добавления JGit', '2026-07-18 21:01:19.071770', 16, FALSE, FALSE, 'ASSISTANT', NULL, NULL);

ALTER TABLE chat_message ALTER COLUMN id RESTART WITH 1654;

-- ── tool_call_index (callId -> chat_message id, see ChatMemoryService#findToolCallDetail) ──
INSERT INTO tool_call_index (id, conversation_id, call_id, message_id, response_message_id) VALUES
    (500, 'c5dfa618-0ad2-4845-a976-ada46c50f9a4', 'call_01_obJfkGBZNNbS6dGMq5ds4538', 1639, 1640),
    (501, 'c5dfa618-0ad2-4845-a976-ada46c50f9a4', 'call_00_NksUXPjSRp5j2MDcJryA0234', 1641, 1642),
    (502, 'c5dfa618-0ad2-4845-a976-ada46c50f9a4', 'call_00_FTMh2npDo459NqP8edYu6973', 1643, 1644),
    (503, 'c5dfa618-0ad2-4845-a976-ada46c50f9a4', 'call_01_0DvT7d12yVkJs6134bZK5223', 1647, 1648),
    (504, 'c5dfa618-0ad2-4845-a976-ada46c50f9a4', 'call_00_P0jBk6jl0P6VTuGpfGDp1663', 1649, 1650),
    (505, 'c5dfa618-0ad2-4845-a976-ada46c50f9a4', 'call_00_b8FfPSBSjIgVT8Mfzbt20523', 1651, 1652);

ALTER TABLE tool_call_index ALTER COLUMN id RESTART WITH 506;

-- ── documents (75 'анализ' + 76 'Хронология...' captured; 77 is hand-added — 
--    exercises file links, doc links (incl. anchor) and an external link) ──────
INSERT INTO documents (id, title, type, parent_id, updated_at, description, position, is_system, version, summary, summary_source_version, description_version) VALUES
    (75, 'анализ', 'folder', NULL, '2026-07-18 18:00:55.850512+00:00', NULL, 5, FALSE, 1, NULL, NULL, 1),
    (76, 'Хронология изменений backend/build.gradle', 'document', 75, '2026-07-18 18:01:16.433971+00:00', '# Хронология изменений [`backend/build.gradle`](/files?path=backend/build.gradle)

Всего **17 коммитов** за период 20.05–13.07.2026.

---

## 1. Начальный сетап
**`33195a2`** — 20.05.2026 — *Init frontend/backend*

Создание файла с нуля (79 строк):
- **Spring Boot 3.5.7**, Java 25
- **Spring AI 1.1.5** (OpenAI + chat memory JDBC)
- PostgreSQL (`org.postgresql:postgresql:42.7.11`)
- Flexmark (Markdown-парсинг)
- Guava 33.5.0, JSpecify, Lombok
- Spotless 6.25.0 (Google Java Format AOSP)

---

## 2. Семантический поиск
**`f3a158d`** — 20.05.2026 — *Semantic search*

- Spring AI 1.1.5 → **1.1.6**

---

## 3. Flyway-миграции и системные документы
**`20a0562`** — 21.05.2026 — *feat: add system-protected documents and Flyway migrations*

- ➕ `org.flywaydb:flyway-core`
- ➕ `org.flywaydb:flyway-database-postgresql`
- ➖ Удалены Flexmark-зависимости (больше не нужны)

---

## 4. Интеграция фронтенда в bootJar
**`3bf86e2`** — 21.05.2026 — *build: integrate frontend build into backend bootJar*

- `bootJar` теперь зависит от `:frontend:copyFrontendBuild`

---

## 5. Docker, Actuator, внешняя конфигурация БД
**`54f403d`** — 22.05.2026 — *build: add Docker setup, externalize DB config, add Actuator, refine frontend build*

- ➕ `spring-boot-starter-actuator`
- ➖ Убран `netty-resolver-dns-native-macos`
- Задача фронтенда переименована: `copyFrontendBuild` → `copyFrontend`

---

## 6. Рефакторинг зависимостей
**`dfed77c`** — 22.05.2026 — *refactor*

- Версия PostgreSQL убрана (управляется BOM)
- Стиль кавычек унифицирован (скобки `()` убраны)

---

## 7. Dependabot: Spotless
**`166b0a2`** — 23.05.2026 — *bump com.diffplug.spotless from 6.25.0 to 8.5.1*

- Spotless: версия убрана (теперь через BOM)

---

## 8. Dependabot: Guava
**`18c9b31`** — 23.05.2026 — *bump the minor-and-patch group with 2 updates*

- Guava 33.5.0 → **33.6.0**

---

## 9. Tree-sitter для file outline
**`5998209`** — 25.05.2026 — *feature: add file outline tool with tree-sitter and regex fallback*

- ➕ `io.github.bonede:tree-sitter:0.26.6`
- ➕ `tree-sitter-java:0.23.5`
- ➕ `tree-sitter-javascript:0.23.1`
- ➕ `tree-sitter-typescript:0.23.2`
- ➕ `tree-sitter-python:0.25.0`
- Стиль кавычек унифицирован (двойные → одинарные)

---

## 10. Dependabot: Spring Boot + tree-sitter-js
**`af2f81b`** — 25.05.2026 — *bump the minor-and-patch group with 2 updates*

- Spring Boot 3.5.7 → **3.5.14**
- tree-sitter-javascript 0.23.1 → **0.25.0**

---

## 11. H2 для standalone-развёртывания
**`359892c`** — 28.05.2026 — *feature: add embedded H2 profile for standalone deployment*

- ➕ `com.h2database:h2`

---

## 12. Testcontainers для интеграционных тестов
**`1d73bdf`** — 14.06.2026 — *test(backend): add Testcontainers PostgreSQL integration tests*

- ➕ `org.testcontainers:postgresql`
- ➕ `org.testcontainers:junit-jupiter`

---

## 13. Dependabot: Spring Boot patch
**`38e5ba2`** — 17.06.2026 — *bump org.springframework.boot from 3.5.14 to 3.5.15*

- Spring Boot 3.5.14 → **3.5.15**

---

## 14. HTTP Basic Auth
**`5cb08b9`** — 20.06.2026 — *Add HTTP Basic auth with single in-memory user from config*

- ➕ `spring-boot-starter-security`
- ➕ `spring-security-test`

---

## 15. ⚠️ **КЛЮЧЕВОЕ:** Spring Boot 4.1 + Spring AI 2.0
**`10aaf73`** — 22.06.2026 — *Upgrade to Spring Boot 4.1 and Spring AI 2.0 (#50)*

Самое масштабное обновление (18 строк добавлено, 2 удалено):

- **Spring Boot 3.5.15 → 4.1.0**
- **Spring AI 1.1.6 → 2.0.0**
- ➕ `spring-boot-flyway` — в SB4 `FlywayAutoConfiguration` вынесен в отдельный модуль
- ➕ `jackson-datatype-jsr310` — SB4 перешёл на Jackson 3, но внутренняя сериализация (ChatMemoryService, Spring Data JDBC, tool-call persistence) всё ещё требует Jackson 2
- ➕ `spring-boot-data-jdbc-test` + `spring-boot-jdbc-test` — тестовые слайсы вынесены из автоконфигурации
- ➕ `testcontainers-bom:1.21.4` — SB4 больше не управляет версиями Testcontainers

---

## 16. Фиксы сборки фронтенда
**`ccb7fe3`** — 12.07.2026 — *Improve web sandbox build & test guidance (#76)*

- Обычный `jar` теперь тоже зависит от `:frontend:copyFrontend` (валидация implicit dependency)
- `compileTestJava` и `test` → `mustRunAfter :frontend:copyFrontend`

---

## 17. JGit вместо git subprocess
**`8547d56`** — 13.07.2026 — *Replace git subprocess calls with JGit (#79)*

- ➕ `org.eclipse.jgit:7.7.0` — in-process Git для GitService (кроме `git grep`)

---

## Сводка по типам изменений

| Тип | Количество |
|---|---|
| Новые зависимости | 16 |
| Обновления версий (dependabot) | 4 |
| Удаление зависимостей | 3 (flexmark × 3) |
| Мажорные миграции | 1 (SB4.1 + SA2.0) |
| Инфраструктура сборки | 4 (bootJar, jar, test ordering) |
', 0, FALSE, 1, NULL, NULL, 1),
    (77, 'Пример: ссылки на файлы и документы', 'document', 75, '2026-07-18 18:05:00.000000+00:00', '# Пример: ссылки на файлы и документы

Этот документ добавлен вручную (не через AI-инструменты) для проверки рендеринга ссылок и вложений.

- Ссылка на файл в репозитории: [backend/build.gradle](/files?path=backend/build.gradle)
- Ссылка на другой документ базы знаний: [Хронология изменений backend/build.gradle](/?doc=76)
- Ссылка на документ с якорем на раздел: [Хронология — итоговая сводка](/?doc=76#сводка-по-типам-изменений)
- Внешняя ссылка (открывается в новой вкладке): [Spring AI Reference](https://docs.spring.io/spring-ai/reference/)

К документу также прикреплён файл-вложение — фрагмент `build.gradle` (см. вкладку «Вложения»).
', 1, FALSE, 1, NULL, NULL, 1);

ALTER TABLE documents ALTER COLUMN id RESTART WITH 78;

-- ── document_history ─────────────────────────────────────────────────────
INSERT INTO document_history (id, document_id, version, title, type, description, updated_at, summary, summary_source_version, description_version) VALUES
    (375, 75, 1, 'анализ', 'folder', NULL, '2026-07-18 18:00:55.850512+00:00', NULL, NULL, 1),
    (376, 76, 1, 'Хронология изменений backend/build.gradle', 'document', '# Хронология изменений [`backend/build.gradle`](/files?path=backend/build.gradle)

Всего **17 коммитов** за период 20.05–13.07.2026.

---

## 1. Начальный сетап
**`33195a2`** — 20.05.2026 — *Init frontend/backend*

Создание файла с нуля (79 строк):
- **Spring Boot 3.5.7**, Java 25
- **Spring AI 1.1.5** (OpenAI + chat memory JDBC)
- PostgreSQL (`org.postgresql:postgresql:42.7.11`)
- Flexmark (Markdown-парсинг)
- Guava 33.5.0, JSpecify, Lombok
- Spotless 6.25.0 (Google Java Format AOSP)

---

## 2. Семантический поиск
**`f3a158d`** — 20.05.2026 — *Semantic search*

- Spring AI 1.1.5 → **1.1.6**

---

## 3. Flyway-миграции и системные документы
**`20a0562`** — 21.05.2026 — *feat: add system-protected documents and Flyway migrations*

- ➕ `org.flywaydb:flyway-core`
- ➕ `org.flywaydb:flyway-database-postgresql`
- ➖ Удалены Flexmark-зависимости (больше не нужны)

---

## 4. Интеграция фронтенда в bootJar
**`3bf86e2`** — 21.05.2026 — *build: integrate frontend build into backend bootJar*

- `bootJar` теперь зависит от `:frontend:copyFrontendBuild`

---

## 5. Docker, Actuator, внешняя конфигурация БД
**`54f403d`** — 22.05.2026 — *build: add Docker setup, externalize DB config, add Actuator, refine frontend build*

- ➕ `spring-boot-starter-actuator`
- ➖ Убран `netty-resolver-dns-native-macos`
- Задача фронтенда переименована: `copyFrontendBuild` → `copyFrontend`

---

## 6. Рефакторинг зависимостей
**`dfed77c`** — 22.05.2026 — *refactor*

- Версия PostgreSQL убрана (управляется BOM)
- Стиль кавычек унифицирован (скобки `()` убраны)

---

## 7. Dependabot: Spotless
**`166b0a2`** — 23.05.2026 — *bump com.diffplug.spotless from 6.25.0 to 8.5.1*

- Spotless: версия убрана (теперь через BOM)

---

## 8. Dependabot: Guava
**`18c9b31`** — 23.05.2026 — *bump the minor-and-patch group with 2 updates*

- Guava 33.5.0 → **33.6.0**

---

## 9. Tree-sitter для file outline
**`5998209`** — 25.05.2026 — *feature: add file outline tool with tree-sitter and regex fallback*

- ➕ `io.github.bonede:tree-sitter:0.26.6`
- ➕ `tree-sitter-java:0.23.5`
- ➕ `tree-sitter-javascript:0.23.1`
- ➕ `tree-sitter-typescript:0.23.2`
- ➕ `tree-sitter-python:0.25.0`
- Стиль кавычек унифицирован (двойные → одинарные)

---

## 10. Dependabot: Spring Boot + tree-sitter-js
**`af2f81b`** — 25.05.2026 — *bump the minor-and-patch group with 2 updates*

- Spring Boot 3.5.7 → **3.5.14**
- tree-sitter-javascript 0.23.1 → **0.25.0**

---

## 11. H2 для standalone-развёртывания
**`359892c`** — 28.05.2026 — *feature: add embedded H2 profile for standalone deployment*

- ➕ `com.h2database:h2`

---

## 12. Testcontainers для интеграционных тестов
**`1d73bdf`** — 14.06.2026 — *test(backend): add Testcontainers PostgreSQL integration tests*

- ➕ `org.testcontainers:postgresql`
- ➕ `org.testcontainers:junit-jupiter`

---

## 13. Dependabot: Spring Boot patch
**`38e5ba2`** — 17.06.2026 — *bump org.springframework.boot from 3.5.14 to 3.5.15*

- Spring Boot 3.5.14 → **3.5.15**

---

## 14. HTTP Basic Auth
**`5cb08b9`** — 20.06.2026 — *Add HTTP Basic auth with single in-memory user from config*

- ➕ `spring-boot-starter-security`
- ➕ `spring-security-test`

---

## 15. ⚠️ **КЛЮЧЕВОЕ:** Spring Boot 4.1 + Spring AI 2.0
**`10aaf73`** — 22.06.2026 — *Upgrade to Spring Boot 4.1 and Spring AI 2.0 (#50)*

Самое масштабное обновление (18 строк добавлено, 2 удалено):

- **Spring Boot 3.5.15 → 4.1.0**
- **Spring AI 1.1.6 → 2.0.0**
- ➕ `spring-boot-flyway` — в SB4 `FlywayAutoConfiguration` вынесен в отдельный модуль
- ➕ `jackson-datatype-jsr310` — SB4 перешёл на Jackson 3, но внутренняя сериализация (ChatMemoryService, Spring Data JDBC, tool-call persistence) всё ещё требует Jackson 2
- ➕ `spring-boot-data-jdbc-test` + `spring-boot-jdbc-test` — тестовые слайсы вынесены из автоконфигурации
- ➕ `testcontainers-bom:1.21.4` — SB4 больше не управляет версиями Testcontainers

---

## 16. Фиксы сборки фронтенда
**`ccb7fe3`** — 12.07.2026 — *Improve web sandbox build & test guidance (#76)*

- Обычный `jar` теперь тоже зависит от `:frontend:copyFrontend` (валидация implicit dependency)
- `compileTestJava` и `test` → `mustRunAfter :frontend:copyFrontend`

---

## 17. JGit вместо git subprocess
**`8547d56`** — 13.07.2026 — *Replace git subprocess calls with JGit (#79)*

- ➕ `org.eclipse.jgit:7.7.0` — in-process Git для GitService (кроме `git grep`)

---

## Сводка по типам изменений

| Тип | Количество |
|---|---|
| Новые зависимости | 16 |
| Обновления версий (dependabot) | 4 |
| Удаление зависимостей | 3 (flexmark × 3) |
| Мажорные миграции | 1 (SB4.1 + SA2.0) |
| Инфраструктура сборки | 4 (bootJar, jar, test ordering) |
', '2026-07-18 18:01:16.433971+00:00', NULL, NULL, 1),
    (377, 77, 1, 'Пример: ссылки на файлы и документы', 'document', '# Пример: ссылки на файлы и документы

Этот документ добавлен вручную (не через AI-инструменты) для проверки рендеринга ссылок и вложений.

- Ссылка на файл в репозитории: [backend/build.gradle](/files?path=backend/build.gradle)
- Ссылка на другой документ базы знаний: [Хронология изменений backend/build.gradle](/?doc=76)
- Ссылка на документ с якорем на раздел: [Хронология — итоговая сводка](/?doc=76#сводка-по-типам-изменений)
- Внешняя ссылка (открывается в новой вкладке): [Spring AI Reference](https://docs.spring.io/spring-ai/reference/)

К документу также прикреплён файл-вложение — фрагмент `build.gradle` (см. вкладку «Вложения»).
', '2026-07-18 18:05:00.000000+00:00', NULL, NULL, 1);

ALTER TABLE document_history ALTER COLUMN id RESTART WITH 378;

-- ── attachments (hand-added: one chat attachment, one document attachment) ──
INSERT INTO attachments (id, owner_type, document_id, conversation_id, file_name, content_type, file_size, content, summary, source_url, created_at, updated_at) VALUES
    (1, 'chat', NULL, 'c5dfa618-0ad2-4845-a976-ada46c50f9a4', 'gradle-build-error.log', 'text/plain', 456, '> Task :backend:compileJava FAILED

FAILURE: Build failed with an exception.

* What went wrong:
Execution failed for task '':backend:compileJava''.
> invalid flag: --enable-preview
  (ToolTranslationsTest uses unnamed variables `_`, a Java 21 preview feature
   finalized in 22-25; toolchain resolved to Java 21 - run with
   --init-script gradle/java21.gradle to enable preview features)

* Try:
> Run with --scan to get full insights.

BUILD FAILED in 4s
', NULL, NULL, '2026-07-18 20:58:50.000000+00:00', '2026-07-18 20:58:50.000000+00:00'),
    (2, 'document', 77, NULL, 'build.gradle', 'text/x-gradle', 553, 'plugins {
    id ''java''
    id ''org.springframework.boot'' version ''4.1.0''
    id ''io.spring.dependency-management'' version ''1.1.7''
    id ''com.diffplug.spotless''
}

dependencies {
    implementation ''org.springframework.boot:spring-boot-starter-web''
    implementation ''org.springframework.boot:spring-boot-starter-security''
    implementation ''org.springframework.boot:spring-boot-starter-actuator''
    implementation ''org.springframework.ai:spring-ai-starter-model-openai''
    implementation ''org.eclipse.jgit:org.eclipse.jgit:7.7.0.202606012155-r''
}
', 'Фрагмент backend/build.gradle: блок plugins и часть зависимостей.', NULL, '2026-07-18 21:05:00.000000+00:00', '2026-07-18 21:05:00.000000+00:00');

ALTER TABLE attachments ALTER COLUMN id RESTART WITH 3;

-- ── embedding_tasks (hand-added: pending task for document 77) ──────────────
INSERT INTO embedding_tasks (id, entity_type, entity_id, status, attempts, created_at, updated_at) VALUES
    (1, 'document', 77, 'pending', 0, '2026-07-18 21:05:00.000000', '2026-07-18 21:05:00.000000');

ALTER TABLE embedding_tasks ALTER COLUMN id RESTART WITH 2;

