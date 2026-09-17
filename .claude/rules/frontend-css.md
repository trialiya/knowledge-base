---
paths:
  - "frontend/src/**/*.css"
---

# Frontend styles

- **Colour comes from a role, never from a value.** `--kb-text-muted`,
  `--kb-border-error`, `--kb-shadow-popover` — declared in
  `styles/theme-light.css`, resolved from `styles/palette.css`. A literal
  (`#888`, `rgba(0, 0, 0, .05)`) and a raw palette entry (`--p-neutral-600`)
  are equally wrong here: both survive a theme swap unchanged, and a theme is
  exactly what the roles exist for. A shadow is a role in full, not a colour
  inside one — a dark theme wants a different radius and opacity, not the same
  black. `styles/tokens.test.js` enforces all of it, and also fails on a role or
  a palette entry nobody asks for. Need a shade the dictionary has no word for?
  Add the role next to its neighbours **in both `theme-light.css` and
  `theme-dark.css`**, with a comment saying what it marks — don't inline the
  value. A role declared in only one theme does not fail loudly: the light theme
  sits on a bare `:root`, so a role missing from the dark one silently stays
  light, and you find it by eye on the one screen you happened to open.

  The dark theme is not the light one inverted. Its neutrals are a separate,
  untinted ramp (`--p-gray-*`) and its accent is blue, because the light theme's
  violet tint gathers into a lilac cast across large dark surfaces. Raised
  surfaces go *lighter* there, not darker; status fills go paler and their
  surfaces go muted; shadows are black, since a coloured shadow on a dark
  background reads as a glow.
- **There is no global `box-sizing: border-box`** in this project. Any rule that
  sizes a box (`min-height`, `height`, `width`) must set `box-sizing` itself, or
  padding and border silently add to it — and `<button>`s behave differently from
  `<div>`s, since the UA stylesheet already gives buttons `border-box`.
- One naming scheme: BEM (`block__element--modifier`), lowercase-hyphenated block
  names. No new abbreviated prefixes (`tcd-`, `fcd-`, `set-`).
- CSS is plain — no modules, no preprocessor — so classes are global. Prefix with
  the block name to avoid collisions, and never reference another panel's
  classes; shared chrome belongs in `common/`.
- File layout: shared styles sit next to their component in `common/`; panel
  styles go in `<panel>/styles/<topic>.css`, one topic per file, behind an import
  barrel (`chatPanel/styles/` behind `chatWindow.css`, and
  `knowledgeBasePanel/styles/`). Don't grow monolithic per-panel files.
- Layout metrics are tokens on `.workspace` (`--ws-gutter`, `--ws-row-min-h`,
  `--ws-row-font`, `--ws-indent`, `--ws-right-width`) so the panel head, the
  action button, the search widget and the rows all sit on one vertical. A
  section may override a token from its own `.workspace--*` modifier, but only
  with a comment saying why — `.workspace--files` narrows `--ws-indent` because
  repo paths are deep, `.workspace--kb` widens `--ws-right-width` because its
  right panel carries four tabs.
- `--ws-left-width` is **not** one of those: it lives on `:root` because the drag
  handle rewrites it there for every section at once. A `.workspace--*` override
  would outrank `:root` and freeze that section's width — never redeclare it.
- `components/common/ui/buttons.css` is the only place button looks live, and
  `common/modal/modalShell.css` the only place modal chrome does. The
  panel-local families (`set-btn`, `detail-icon-btn`, `new-chat-button`,
  `md-toolbar__btn`, `message-copy-btn`, `code-block__copy`, `phrases-cat-btn`,
  `contents-pagination__btn`, `header-menu__trigger`, `modal-overlay`,
  `tcd-overlay`, …) have been folded into them — keep it that way. What may
  stay panel-local is what belongs to the row rather than to the button: where
  it sits (`margin-left: auto`), when it appears (the tool-call copy button
  waits for the plate to be hovered), how wide it stretches. Written as a
  two-class selector (`.icon-btn.info-list__copy-btn`) when it overrides the
  shared rule, because specificity is equal there and otherwise the winner is
  decided by the accident of import order. Chrome that two surfaces must not
  let drift apart gets the same treatment: `common/ui/gitChrome.css` holds the repo-state chrome shown both
  over the file tree and in chat's Repo tab.
