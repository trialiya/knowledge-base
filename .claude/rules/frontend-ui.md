---
paths:
  - "frontend/src/**/*.js"
  - "frontend/src/**/*.jsx"
---

# Frontend conventions (`frontend/src`)

Target state for anyone touching frontend code. Follow these for all new code and
migrate existing code as you touch it (see "Migrate on touch" in `CLAUDE.md`),
never in big-bang rewrites.

## Section shell

- Every section — chat, knowledge base, files, **and** Settings/Admin — renders
  through the shared `<WorkspaceLayout>` (`components/common/`). It owns the
  section container, the collapsible left panel (title · action · toolbar ·
  body), the center area and the right panel: a drawer collapsed by default that
  shows as an icon rail, with `<RightPanel>` rendering the expanded form.
  Sections supply slot content only — never rebuild the split, and never
  reintroduce per-section container classes.
- Panel metrics are CSS variables on `.workspace` (`--ws-right-width` and the row
  tokens); a section may tune them from its own `.workspace--*` modifier.
  `--ws-left-width` is the exception: it lives on `:root` because the drag handle
  rewrites it there for every section at once, so a `.workspace--*` override
  would outrank `:root` and freeze that section's width. Never redeclare it.
- The center header is the shared `.workspace__head`, defined in one rule
  together with the left/right `.workspace__side-head` so all three columns start
  on one line. Keep them in that one rule — two copies drift and the step at the
  column seam comes back. It is **one row** that must not wrap: the object's name
  in `.workspace__head-title` (ellipsis; `.workspace__head-edit` for its inline
  rename form), buttons in `.workspace__head-actions`. Metadata does not go there
  — dates, versions, path and author belong to the right panel's Info tab.
  `SettingsShell`'s head is deliberately two rows and does not line up; migrate
  it when its subtitle finds another home, and don't copy its metrics anywhere.
- A path in a header is the shared `<HeadCrumbs>` (`common/HeadCrumbs.jsx`).
  Sections pass `items` (`{ key, label, onNavigate? }` — an item without
  `onNavigate` is the current one) and `trailingSep` when the header's own title
  continues the chain (knowledge base) rather than ending it (files). Pass the
  **whole** chain: overflow is HeadCrumbs' business — the middle collapses into
  one clickable `…` (`utils/breadcrumbs.js`), and what still doesn't fit scrolls
  horizontally pinned to the **end**, because the nearest folder and the open
  object matter more than the root the left tree already shows. Collapsing is
  decided by measuring (`scrollWidth` against a `ResizeObserver` on
  `.workspace__head`, never on the crumbs themselves — their width follows their
  content and observing it loops), never by counting crumbs. Don't write a third
  breadcrumb.
- The right panel holds everything *about* the thing; the center is the thing
  itself. Every section opens with an **Info** tab, first and always, rendered
  through the shared `<InfoList>` — sections pass `[{ label, value, mono, block }]`
  rows and it drops the empty ones. Don't hand-roll another `dl`. Info carries
  dates, AI topic and model in chat; type, dates and versions in the knowledge
  base; path metadata plus the last commit that touched the file in files. Then
  come the per-section tabs: chat → attachments; knowledge base → summary, folder
  contents, attachments (built by `detailSidebar.jsx`); files → nothing else yet.
  Tab keys shared
  across sections live in `constants/rightTabs.js` (`RIGHT_TAB`) so `?right=info`
  means the same thing everywhere; `DOC_TAB` holds the knowledge-base-only ones
  and is right-panel keys, not center tabs.
- State shared by the center and the right panel (the KB content draft,
  fullscreen, history) lives in `useDetailPanel`, hoisted to `KnowledgeBase`. It
  is no longer remounted per document, so it resets on `nodeId` change itself —
  keep that reset when adding state to it.

## URL scheme

`useAppNavigation` is the only owner of navigation state and the only writer of
`window.history`. The **path carries the opened resource**, the **query carries
screen state**:

```
/chat/<chatId>                   /knowledge/doc/<docId>
/knowledge/search?q=&mode=       /files/<path/to/file>
/admin  /settings                (+ ?left=0 / ?right=<tab> anywhere)
```

Only non-default values are written, so addresses stay short. Old query-form
links (`?view=`, `?doc=`, `?path=`, `?chat=`, `?tab=`) still open and are
canonicalized on load — keep that fallback when touching `readUrl`. Panel toggles
use `replaceState` (they are not navigation); real transitions use `pushState`.
The write mode is set by whoever triggers the change (`pushNav`/`replaceNav`) and
is never reset from the write effect — a `setNav` that bails out would otherwise
leak the mode into the next real transition. Adding a new top-level path means
updating `SpaForwardController` too; its mappings must cover nested paths.

Panel open/closed state is **controlled state that lives in the URL**
(`?left=0`, `?right=<tab>`), owned by `useAppNavigation` and threaded down as the
`panels` prop from `App`; per-section layout is remembered in `localStorage`
(`panelState.js`). Never keep it locally in a section.

## Left panel: lists and trees

- Every row — chat list, knowledge tree, file tree, settings groups — is the
  shared `.ws-item` from `common/sidePanel.css`. A section adds only modifiers
  for its own behaviour **on top of** the shared block (the KB tree's
  `.tree-row--dragging`, the file tree's `.ws-item--nowrap`), never a parallel
  row family of its own.
- Row height is `min-height` only, never padding plus content: a row with an
  action button would otherwise be taller than one without.
- Search above the list is the shared `<PanelSearch>` (trigger → field → portal
  dropdown, built on `useSearchDropdown`). A section passes only `search` (fetch)
  and `describeItem` (icon/title/subtitle/badge); common labels live in
  `common.json` under `panelSearch.*`. Don't write another search widget —
  `ChatSearch`/`FileSearch`/`TreeSearch` are 40-line adapters.
- Keyboard: the list **container** is the single tab stop (`tabIndex={0}` +
  `onKeyDown={useListNavigation()}`); rows carry `data-ws-item` + `tabIndex={-1}`
  and are reached with arrows (Enter/Space opens, ←/→ collapses/expands through
  `[data-ws-chevron]`, Home/End jump). Rows cannot be `<button>`s — they already
  contain action buttons — so this is how they become reachable at all; keep the
  attributes when adding a new kind of row. The settings list is the one
  exception: its rows *are* `<button>`s, so its container keeps no `tabIndex` and
  arrows merely supplement native Tab/Enter.
- ARIA: trees are `role="tree"`/`treeitem` with `aria-level`/`aria-expanded`/
  `aria-selected`, flat lists are `role="listbox"`/`option`, and layout wrappers
  between them carry `role="none"` so the rows stay owned by the tree.
- `useListNavigation` scrolls the focused row into view **vertically only**
  (`focus({ preventScroll: true })` plus a manual `scrollTop` nudge). Don't swap
  it back to `scrollIntoView`: file-tree rows are wider than the panel, and
  `inline: 'nearest'` resets their horizontal scroll to 0.
- The left panel's width lives in one shared store (`useLeftPanelWidth`), not in
  component state or the URL: several `WorkspaceLayout`s are mounted at once
  (chat and knowledge base always are), so a per-instance width would make the
  panel edge jump between sections. Dragging writes the `--ws-left-width` CSS
  variable on `:root` directly and only commits to the store on pointer-up — a
  `setState` per `pointermove` would re-render the whole section.

## Modals and buttons

- Use the shared `<ModalShell>` for every dialog. It owns `createPortal` to
  `document.body`, the overlay and backdrop-close, Escape via `useEscape`,
  `role="dialog"`/`"alertdialog"` + `aria-modal`, and the shared modal CSS.
  Components supply header/body/footer content only. Do not introduce new
  `*-overlay` classes or per-modal overlay divs.
- Backdrop close is `onMouseDown`, not `onClick`, so a text selection that ends
  outside the modal doesn't dismiss it.
- Use the shared button classes from `components/common/buttons.css`: `btn`,
  `btn--primary`, `btn--ghost`, `btn--danger`, `btn--sm`, and `icon-btn`
  (+ `icon-btn--danger`, `icon-btn--done`, `icon-btn--star`) for icon-only
  buttons. Don't add new button families.

## Components and hooks

- Components render; hooks own state and API orchestration; pure logic goes in
  plain `.js` modules next to the feature (`treeOps.js`, `fileChips.js`).
- Keep files focused: a file approaching ~300 lines or holding 2+ exported
  components is due for a split — `wc -l` answers this, so no list of offenders
  is kept here. `ChatWindow.jsx` is the worst of them and the one to chip away at
  when you are in it anyway; only its layout has been extracted so far.
- Reuse the shared hooks before writing new plumbing: `useSearchDropdown`
  (search-button → dropdown widgets), `useEscape`, `useDocPreview`/
  `useFilePreview` (both built on `usePreviewCache` — the module-cache preview
  pattern, which new preview kinds should reuse too), and `useCopyFeedback` for
  any copy-to-clipboard button (`writeText` plus the transient "copied" state and
  its timer). Several components still inline the copy hook's body — migrate one
  when you touch it, don't add another.
- Async effects must be cancellation-aware (a `cancelled` flag or an AbortSignal
  in cleanup), matching the existing preview hooks.
- The two trees are intentionally separate: `knowledgeBasePanel/TreeNode`
  (editable — drag-drop, pagination) versus `filesPanel/FileTreeNode`
  (read-only). Do not unify them.

## Visual checks

Before checking a component by hand, read `frontend/tests/visual/cases.yaml` —
scenarios and data for already-checked components live there, with fixtures
alongside. Add new checks as cases in the same format and never rename an
existing `id`; it is the future story/baseline name. `./run/test.sh smoke` boots
the app and screenshots it — see the `frontend-visual-check` skill.
