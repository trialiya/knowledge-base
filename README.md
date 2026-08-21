# Knowledge Base

An AI assistant for working with a Git repository: search across code and
change history, answers to questions about the project, and a knowledge base
that lives alongside the code.

## What it is

You point it at a local Git repository (requires `git` to be installed) — and
get a web application where an AI chat answers natural-language questions
about the code, commits, and architecture, while the results of that work
accumulate in a structured knowledge base.

Knowledge Base is not a terminal agent. The model doesn't execute commands: it
reads the repository through a fixed set of tools — files, commits, diffs,
grep, structural analysis — and works strictly within the specified
directory, seeing only files under Git control (`.env`, `node_modules`, and
everything in `.gitignore` don't exist for it). Code editing is available but
disabled by default and turned on with an explicit flag; you run the build
and tests yourself — for now.

The core use case is **finding information and accumulating knowledge about
the project**: onboarding into unfamiliar code, digging through change
history, documentation that doesn't drift from the code.

<p align="center">
  <img src="docs/assets/chat.png" alt="AI chat: the assistant reads commit history and creates a document in the knowledge base" width="49%">
  <img src="docs/assets/knowledge-base.png" alt="Knowledge base: a document tree and a Markdown document with the result" width="49%">
</p>

## What it can do

### Repository search and analysis

- 🤖 **AI chat over the code** — natural-language questions about files,
  commits, and architecture; ready-made modes (Analyst / Developer / Tester)
  and model selection
- 🐙 **Git analysis** — reading files, commit history, diffs, grep across the
  repository, structural code analysis (tree-sitter)
- 📂 **"Files" panel** — browse the repository (tree, contents, latest
  commit) in a GitHub-like style, insert files into the chat
- 🔍 **Hybrid search** — keyword + semantic (vector), across the knowledge
  base and across chats

### Knowledge base

- 📁 **Document tree** — folders and documents in a tree structure
- ✏️ **Markdown editor** — creation and editing with live preview
- 🕘 **Version history** — diffs of changes and restoring previous states
- ✨ **AI summarization** — automatic short descriptions of documents
- 📎 **Attachments** — uploading files to documents and chats
- 📤 **Export and import** — exchanging the knowledge base with the file
  system

### Code editing — optional

- 🔒 **Disabled by default** — enabled per project with the `kb.projects[].edit-enabled` flag
- ✍️ **Creating and editing files** in the working tree — only within the
  repository, and only files git tracks; editing the untracked ones a project
  opens for reading (`kb.projects[].allow-globs`) takes a second flag,
  `kb.projects[].untracked-edit-enabled`
- 🚫 **No command execution** — the model doesn't get a shell; you run
  builds and tests yourself (for now)

### Integrations and deployment

- 🔌 **Any OpenAI-compatible API** — including local models: your code
  never has to leave your machine
- 🧩 **MCP** — connect external tools via MCP servers (disabled by default)
- 🐳 **Docker** — a ready-made compose file for quick deployment
- ⚙️ **Administration** — AI/search configuration snapshots, a phrase
  library, reindexing, system information

## Quick start

You'll need: `git` installed, a repository to analyze, and an API key for
any OpenAI-compatible API.

```bash
cd docker
cp example.env .env
# Set AI_API_KEY, AI_BASE_URL, AI_MODEL, and PROJECT_PATH_MOUNT
docker compose -f docker-compose-h2.yaml up
```

Open http://localhost:8080 — default login/password is `admin` / `admin`
(change it in settings if the app is reachable from more than just
localhost).

> This option uses the built-in H2 — PostgreSQL isn't needed. For the full
> stack with semantic search, run `docker compose up` and add the
> `AI_EMBED_*` variables to `.env`.

Without Docker (requires JDK 25): build the JAR and start it with a script
from `run/` —

```bash
./gradlew :backend:bootJar
# Edit run/application.yaml: api-key, base-url, model,
# kb.git.project-path (path to the repository to analyze)
./run/run.sh
```

Step-by-step instructions for both options, profiles, and Windows scripts —
[Installation Guide](docs/проект/руководство-по-установке.md) [RU].

## Documentation

Start with the [Introduction](docs/проект/введение.md) [RU] — it has an
overview of the features and a full table of contents. Key documents:

| Document | About |
|---|---|
| [Architecture](docs/проект/архитектура.md) [RU] | Layer diagram, tech stack, services |
| [AI Tools](docs/проект/ai-инструменты.md) [RU] | All assistant tools and their parameters |
| [Configuration](docs/проект/конфигурация.md) [RU] | Environment variables, Docker, settings |
| [Installation Guide](docs/проект/руководство-по-установке.md) [RU] | Requirements, running, troubleshooting |
| [Chat — User Guide](docs/features/чат-руководство-пользователя.md) [RU] | How to use the chat |
| [Knowledge Base — User Guide](docs/features/база-знаний-руководство-пользователя.md) [RU] | Navigation, search, AI summarization |
| [Development and Contributing](docs/проект/разработка-и-контрибьюция.md) [RU] | Building, testing, code style |

## Tech stack

| Component | Technologies |
|---|---|
| Backend | Java 25, Spring Boot 4.1, Spring AI, PostgreSQL 17 + pgvector |
| Frontend | React 19, CSS |
| Infrastructure | Docker, docker-compose |

Details — [Architecture](docs/проект/архитектура.md) [RU]
