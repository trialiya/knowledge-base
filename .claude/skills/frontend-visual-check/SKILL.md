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

## Two commands, and they answer different questions

```bash
./run/test.sh smoke      # boot the app, drive real screens
./run/test.sh harness    # render one component against a fixture, no backend
```

Reach for **smoke** by default: it shows the real layout with real data, and it
is the only thing that can answer a question about a whole screen — a tab strip,
a panel header, two sections side by side.

Reach for **harness** for a state the running app cannot be asked for on demand:
an unfinished merge, a chat with the model mid-answer, a repository that refuses
a push. Those live in `frontend/tests/visual/fixtures/`, and the harness mounts
one component straight into a page. Case ids are the same `fixtures:` references
`cases.yaml` uses (`<module>#<export>`); the opt-in list is
`frontend/tests/visual/harness/registry.jsx`, and a fixture only becomes
shootable once it is listed there. Shots land in `harness/shots/` (git-ignored),
one per case, and a case whose page logged a console error is reported `✗`.

```bash
./run/test.sh harness -- chatRepo.js#repoTabMerging
```

**The harness can be the thing that is wrong.** It builds its own frame around
the component, and its CSS bundle is ordered by its own import graph, not the
app's. It has already shown a modal at 380px that is 560px in the product (two
rules of equal specificity, opposite order) and browser-default buttons (a
stylesheet the app happened to load from elsewhere). Before reporting anything
it shows as a defect, check it against the built app — `frontend/build/static/*.css`
answers the ordering questions. What the harness is reliably good at is the
opposite direction: it caught both of those, and they were real gaps in the
components' own imports.

`smoke` runs `scripts/playwright-smoke.js`, which builds the JAR itself through
`./run/test.sh jar` before booting it — so running the script directly does the
same thing. Its Usage header has the flags (`--no-seed`, `--no-build`).
`harness` runs `scripts/visual-harness.js`, which builds only the stand's own
Vite bundle and needs no JAR at all; its header has the rest.

## Driving it yourself

Chromium and Playwright are pre-installed in the web sandbox — do **not** run
`playwright install`. Don't reach for `yarn start` either: the Vite dev server
does come up in the sandbox, but on its own it serves a UI with no backend
behind it, so every panel renders empty. Boot the backend JAR (H2 profile, dummy
AI env vars) and drive it with Chromium instead.

`scripts/playwright-smoke.js` is the working, runnable example: it boots the
JAR through `run/run.sh h2,playwright-smoke` (the checked-in
`run/application-playwright-smoke.yaml` profile: disposable database, dummy AI,
this repo as the project), polls `/actuator/health`, logs in over HTTP Basic
(`admin`/`admin`), waits for the SPA to mount and screenshots it. By default it
first seeds `db/sample-data.sql` into the profile's disposable
`local-db/h2-smoke` file — never your real `local-db/h2` — so the screenshot
shows real chat and document content instead of an empty app; pass `--no-seed`
to skip that.

Copy its `chromium.launch()` and backend-launch setup for ad hoc checks beyond
a screenshot. Its header covers the details, including two unrelated "locale"
gotchas:

- **JVM system locale.** The sandbox has no locale configured, so a bare JVM
  defaults to ASCII and `GitService` throws on the non-ASCII repo paths under
  `docs/`. `run.sh` and `test.sh` force `LANG=C.utf8`/`LC_ALL=C.utf8`; a JVM
  started any other way needs the same — always on, not a flag.
- **Browser UI language.** `i18next-browser-languagedetector` reads
  `navigator.language` when nothing is cached in `localStorage` (`kb-lang`),
  and this sandbox's Chromium reports `en-US` with no locale set on the
  context — so a screenshot taken without setting one comes out in English
  even though the app's `fallbackLng` and primary audience are Russian. Fixed
  by passing `locale: 'ru'` to `browser.newContext()`; `playwright-smoke.js`
  does this by default (override with `--locale=en` if the check is
  specifically about the English strings). Copy this into any ad hoc script
  too — it will not happen on its own.
