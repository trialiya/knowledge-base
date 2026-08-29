---
paths:
  - "frontend/src/**/*.css"
---

# Frontend styles

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
  `modal-overlay`, `tcd-overlay`, …) have been folded into them — keep it
  that way. Chrome that two surfaces must not let drift apart gets the same
  treatment: `common/ui/gitChrome.css` holds the repo-state chrome shown both
  over the file tree and in chat's Repo tab.
