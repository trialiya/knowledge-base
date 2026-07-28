---
name: frontend-visual-check
description: Look at the Knowledge Base UI instead of only trusting green tests — boot the app and screenshot it with Chromium, and record the scenario as a case. Read it when asked how a screen actually looks, when checking a frontend change by eye, or before adding a visual scenario.
---

# Checking the UI by eye

## Recorded scenarios come first

Before checking a component by hand, read `frontend/tests/visual/cases.yaml`:
scenarios and data for the already-checked components live there, with fixtures
in `frontend/tests/visual/fixtures/`. Add a new check as a case in the same
format, and **never rename an existing `id`** — it is the future story/baseline
name.

## The one command

```bash
./run/test.sh smoke
```

Runs `scripts/playwright-smoke.js`, which builds the JAR itself through
`./run/test.sh jar` before booting it — so running the script directly does the
same thing. Its Usage header has the flags (`--no-seed`, `--no-build`).

## Driving it yourself

Chromium and Playwright are pre-installed in the web sandbox — do **not** run
`playwright install`. Don't reach for `yarn start` either: the Vite dev server
does come up in the sandbox, but on its own it serves a UI with no backend
behind it, so every panel renders empty. Boot the backend JAR (H2 profile, dummy
AI env vars) and drive it with Chromium instead.

`scripts/playwright-smoke.js` is the working, runnable example: it boots the
JAR, polls `/actuator/health`, logs in over HTTP Basic (`admin`/`admin`), waits
for the SPA to mount and screenshots it. By default it first seeds
`db/sample-data.sql` into a disposable `local-db/h2-smoke` file — never your
real `local-db/h2` — so the screenshot shows real chat and document content
instead of an empty app; pass `--no-seed` to skip that.

Copy its `chromium.launch()` and env-var setup for ad hoc checks beyond a
screenshot. Its header covers the details, including the `LANG=C.utf8` gotcha:
the sandbox has no locale configured, so a bare JVM defaults to ASCII and
`GitService` throws on the non-ASCII repo paths under `docs/`.
