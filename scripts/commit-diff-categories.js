#!/usr/bin/env node
/**
 * Counts lines by category — the changed lines of a commit, a PR or the last
 * N commits, or all lines of a file:
 *   - служебные/сборочные файлы   — total only, no breakdown
 *   - документация                — total only, no breakdown
 *   - основные (исходные) файлы   — broken down into:
 *       import/package · javadoc/комментарии · пустые строки · код
 *     "код" is every line with code outside comments, imports aside; its
 *     "эффективный" part leaves out lines of brackets only (}); ) ]; …) and
 *     "try {", which carry no logic of their own.
 *     A source file is any extension with an entry in SYNTAX below.
 *
 * A changed line is never classified from its diff hunk alone: the hunk can
 * start inside a block comment or a multi-line string with nothing in view
 * to tell. Both sides of every changed source file are read whole (one
 * `git cat-file --batch` for the whole diff), classified top to bottom, and
 * each "-"/"+" line takes the category of its line number on its side.
 *
 * Binary files (images, etc.) carry no diffable lines and are skipped
 * entirely — not printed, not counted anywhere.
 *
 * Usage:
 *   node scripts/commit-diff-categories.js [<commit>] [--file <path>]
 *   node scripts/commit-diff-categories.js --table [<N>]
 *   node scripts/commit-diff-categories.js --pr [<base>] [<head>]
 *   node scripts/commit-diff-categories.js --loc [--rev <rev>] <path>...
 *
 * <commit> defaults to HEAD. --file restricts the report to one path in the
 * commit (repo-relative, as git prints it).
 * --table prints one row per commit for the last N commits (default 10,
 * newest first): file count, the usual +added/-removed, and per-category
 * added-minus-removed deltas. A merge commit gets a row without numbers: what
 * it brings in is the merged branch's commits, counted in their own rows.
 * --pr prints the same table for every commit of <base>..<head> (defaults:
 * origin/main, HEAD) plus a "PR итого" row taken from the net diff between the
 * merge base and <head> — not the sum of the rows, since a line added in one
 * commit and removed in the next is no change to the PR.
 * --loc counts whole files (working tree, or as of <rev> with --rev; paths
 * relative to the current directory either way). It is the measure CLAUDE.md's
 * file-size rule points to.
 */

const { execFileSync } = require("child_process");
const fs = require("fs");
const nodePath = require("path");

const DOC_EXTENSIONS = new Set([".md", ".mdx", ".adoc", ".rst"]);
const DOC_BASENAMES = new Set(["readme", "changelog", "license", "notice"]);

const BUILD_BASENAMES = new Set([
  "build.gradle",
  "build.gradle.kts",
  "settings.gradle",
  "settings.gradle.kts",
  "gradle.properties",
  "gradle.lockfile",
  "gradlew",
  "gradlew.bat",
  "package.json",
  "package-lock.json",
  "yarn.lock",
  ".gitignore",
  ".gitattributes",
  ".editorconfig",
  ".dockerignore",
  "dockerfile",
  "docker-compose.yml",
  "docker-compose.yaml",
  "codeowners",
]);
const BUILD_PATH_PREFIXES = [".github/", ".claude/", "gradle/", "docker/"];
const BUILD_EXTENSIONS = new Set([
  ".json", // mostly i18n locale files — resources, not code
  ".yml",
  ".yaml",
  ".lock",
  ".properties",
  ".toml",
  ".cfg",
  ".ini",
]);

// Comment and string syntax of each source language, with its import forms.
//   line      — tokens that comment out the rest of the line
//   blocks    — [open, close] comment pairs
//   quotes    — one-line string delimiters; comment tokens inside mean nothing,
//               so "/chat/**" is code, not the start of a block comment
//   mlStrings — [open, close] string pairs that may span lines
//   importOpen/importClose — an import whose list wraps onto the next lines
//               (`import {` … `} from "x";`): every line up to the close is import
const C_BLOCK = [["/*", "*/"]];
const JS = {
  line: ["//"],
  blocks: C_BLOCK,
  quotes: ['"', "'"],
  mlStrings: [["`", "`"]],
  regexLiterals: true,
  // Not import.meta or a dynamic import(…), which are expressions.
  importRe:
    /^(import(?=[\s{*"'])|export\s+(\*|\{[^}]*\})\s*from\s+['"][^'"]+['"];?$|(const|let|var)\s+[^=]+=\s*require\(\s*['"][^'"]+['"]\s*\)\s*;?$)/,
  importOpen: /^import\b[^'"]*\{[^}]*$/,
  importClose: /\bfrom\s*['"]/,
};
const SCSS = { line: ["//"], blocks: C_BLOCK, quotes: ['"', "'"], importRe: /^@(import|use|forward)\b/ };
const SH = {
  line: ["#"],
  hashAtWordStart: true, // $#, ${#arr} and a#b are code
  blocks: [],
  quotes: ['"', "'"],
  importRe: /^(source|\.)\s+\S/,
};
const BAT = { lineStartCi: ["rem ", "@rem ", "::"], line: [], blocks: [], quotes: ['"'] };
const HTML = { line: [], blocks: [["<!--", "-->"]], quotes: [] }; // apostrophes in text aren't quotes
const SYNTAX = {
  ".java": {
    line: ["//"],
    blocks: C_BLOCK,
    quotes: ['"', "'"],
    mlStrings: [['"""', '"""']],
    importRe: /^(package|import)\s+\S.*;$/,
  },
  ".js": JS,
  ".jsx": JS,
  ".ts": JS,
  ".tsx": JS,
  ".mjs": JS,
  ".cjs": JS,
  // "//" is not a CSS comment: url(http://…)
  ".css": { line: [], blocks: C_BLOCK, quotes: ['"', "'"], importRe: /^@import\b/ },
  ".scss": SCSS,
  ".less": SCSS,
  ".sql": { line: ["--"], blocks: C_BLOCK, quotes: ["'", '"'] },
  ".sh": SH,
  ".bash": SH,
  ".ps1": {
    line: ["#"],
    hashAtWordStart: true,
    blocks: [["<#", "#>"]],
    quotes: ['"', "'"],
    importRe: /^(Import-Module\b|\.\s+\S)/i,
  },
  ".bat": BAT,
  ".cmd": BAT,
  // A docstring is a string statement, so it is a comment only where nothing
  // precedes it on the line; x = """…""" is a multi-line string, i.e. code.
  ".py": {
    line: ["#"],
    blocks: [['"""', '"""'], ["'''", "'''"]],
    blockAtLineStart: true,
    quotes: ['"', "'"],
    mlStrings: [['"""', '"""'], ["'''", "'''"]],
    importRe: /^(import\s+\S|from\s+\S+\s+import\b)/,
    importOpen: /^from\s+\S+\s+import\s*\([^)]*$/,
    importClose: /\)/,
  },
  ".html": HTML,
  ".htm": HTML,
};

function extOf(path) {
  const base = path.split("/").pop();
  const dot = base.lastIndexOf(".");
  return dot <= 0 ? "" : base.slice(dot).toLowerCase();
}

// `path` is repo-relative with "/" separators — the prefixes depend on it.
function classifyFile(path) {
  const lower = path.toLowerCase();
  const base = lower.split("/").pop();
  const ext = extOf(lower);
  const baseNoExt = ext ? base.slice(0, -ext.length) : base;

  if (DOC_EXTENSIONS.has(ext) || DOC_BASENAMES.has(baseNoExt) || lower.startsWith("docs/")) {
    return "doc";
  }
  if (
    BUILD_BASENAMES.has(base) ||
    BUILD_PATH_PREFIXES.some((p) => lower.startsWith(p)) ||
    BUILD_EXTENSIONS.has(ext)
  ) {
    return "build";
  }
  if (SYNTAX[ext]) {
    return "source";
  }
  return "build"; // anything unrecognized (assets, fixtures, etc.) — general/service bucket
}

function emptyCounts() {
  // "brace" + "effective" together make up the reported "код".
  return { import: 0, comment: 0, empty: 0, brace: 0, effective: 0 };
}

// Lines that only close (or open) a construct: }); ) }; ], — plus "try {".
// Code, but not effective code.
function isBraceOnly(code) {
  const compact = code.replace(/\s+/g, "");
  return /^[()[\]{};,]+$/.test(compact) || compact === "try{";
}

// Index just past the `close` that ends a string whose body starts at `from`,
// honoring backslash escapes; -1 if the line ends first.
function findStringEnd(line, from, close) {
  for (let j = from; j < line.length; j++) {
    if (line[j] === "\\") j++;
    else if (line.startsWith(close, j)) return j + close.length;
  }
  return -1;
}

// A "/" starts a regex literal only where an expression may begin; after a
// value (identifier, number, ")" or "]") it is division.
function regexMayStart(codeSoFar) {
  const prev = codeSoFar.trimEnd();
  return (
    prev === "" ||
    /[(,=:[!&|?{};+\-*%~^]$/.test(prev) ||
    /\b(return|typeof|case|in|of|void|yield|await)$/.test(prev)
  );
}

// Index just past a regex literal starting at `from`, or -1 when the line has
// no closing "/" (then the "/" was not a regex after all).
function findRegexEnd(line, from) {
  let inClass = false;
  for (let j = from + 1; j < line.length; j++) {
    const c = line[j];
    if (c === "\\") j++;
    else if (c === "[") inClass = true;
    else if (c === "]") inClass = false;
    else if (c === "/" && !inClass) {
      let k = j + 1;
      while (k < line.length && /[a-z]/i.test(line[k])) k++;
      return k;
    }
  }
  return -1;
}

// Line state carried between lines: "normal", "c" + close token while inside
// a block comment (e.g. "c*/"), "s" + close token inside a multi-line string.
//
// Splits one trimmed line into its code part (string and regex literals kept
// verbatim, comments cut out) and reports whether any comment was on it.
function scanLine(line, state, syntax) {
  if (state === "normal" && syntax.lineStartCi) {
    const lower = line.toLowerCase();
    if (syntax.lineStartCi.some((t) => lower.startsWith(t) || lower === t.trim())) {
      return { code: "", comment: true, state };
    }
  }
  let code = "";
  let comment = false;
  let open = state === "normal" ? null : state;
  let i = 0;
  scan: while (i < line.length) {
    if (open) {
      const close = open.slice(1);
      let end;
      if (open[0] === "c") {
        comment = true;
        const idx = line.indexOf(close, i);
        end = idx === -1 ? -1 : idx + close.length;
      } else {
        end = findStringEnd(line, i, close);
        code += line.slice(i, end === -1 ? line.length : end);
      }
      if (end === -1) break;
      i = end;
      open = null;
      continue;
    }
    for (const t of syntax.line) {
      if (line.startsWith(t, i) && (!syntax.hashAtWordStart || i === 0 || /\s/.test(line[i - 1]))) {
        comment = true;
        break scan;
      }
    }
    for (const [tok, close] of syntax.blocks) {
      if (line.startsWith(tok, i) && (!syntax.blockAtLineStart || code.trim() === "")) {
        comment = true;
        open = "c" + close;
        i += tok.length;
        continue scan;
      }
    }
    for (const [tok, close] of syntax.mlStrings || []) {
      if (line.startsWith(tok, i)) {
        code += tok;
        open = "s" + close;
        i += tok.length;
        continue scan;
      }
    }
    const ch = line[i];
    let end = -1;
    if (syntax.quotes.includes(ch)) {
      end = findStringEnd(line, i + 1, ch);
      if (end === -1) end = line.length; // unterminated: the rest of the line is the string
    } else if (ch === "/" && syntax.regexLiterals && regexMayStart(code)) {
      end = findRegexEnd(line, i);
    }
    if (end !== -1) {
      code += line.slice(i, end);
      i = end;
      continue;
    }
    code += ch;
    i++;
  }
  return { code: code.trim(), comment, state: open || "normal" };
}

function categorize(scan, syntax) {
  if (scan.code === "") return "comment";
  // JSX comment {/* … */}: the braces are only the comment's wrapper.
  if (scan.comment && scan.code.replace(/\s+/g, "") === "{}") return "comment";
  if (syntax.importRe && syntax.importRe.test(scan.code)) return "import";
  if (isBraceOnly(scan.code)) return "brace";
  return "effective";
}

// Category of every line of a source file's text, top to bottom.
function classifyText(text, syntax) {
  const lines = text.split("\n");
  if (lines[lines.length - 1] === "") lines.pop(); // trailing newline
  let state = "normal";
  let inImportList = false;
  return lines.map((raw) => {
    const trimmed = raw.trim();
    if (trimmed === "") return "empty";
    const scan = scanLine(trimmed, state, syntax);
    state = scan.state;
    if (inImportList) {
      if (syntax.importClose.test(scan.code)) inImportList = false;
      return scan.code === "" ? "comment" : "import";
    }
    const category = categorize(scan, syntax);
    if (category === "import" && syntax.importOpen && syntax.importOpen.test(scan.code)) {
      inImportList = true;
    }
    return category;
  });
}

// Returns a Buffer. A failing command throws; `quiet` keeps git's own stderr
// off the terminal when the caller reports the failure itself.
function git(args, { input, quiet } = {}) {
  return execFileSync("git", ["-c", "core.quotePath=false", ...args], {
    input,
    maxBuffer: 1024 * 1024 * 512,
    stdio: ["pipe", "pipe", quiet ? "pipe" : "inherit"],
  });
}

// Contents of many blobs in one `git cat-file --batch` call.
function readBlobs(ids) {
  const texts = new Map();
  if (ids.length === 0) return texts;
  const out = git(["cat-file", "--batch"], { input: ids.join("\n") + "\n" });
  let pos = 0;
  while (pos < out.length) {
    const eol = out.indexOf(0x0a, pos);
    const [id, type, size] = out.subarray(pos, eol).toString("utf8").split(" ");
    if (type === "missing") throw new Error(`git cat-file: объект ${id} не найден`);
    const start = eol + 1;
    texts.set(id, out.subarray(start, start + Number(size)).toString("utf8"));
    pos = start + Number(size) + 1; // content is followed by "\n"
  }
  return texts;
}

function parseHunkHeader(line) {
  const m = /^@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@/.exec(line);
  return { oldStart: Number(m[1]), newStart: Number(m[2]) };
}

function splitHunks(bodyLines) {
  const hunks = [];
  let current = null;
  for (const line of bodyLines) {
    if (line.startsWith("@@ ")) {
      current = [line];
      hunks.push(current);
    } else if (current && (line[0] === " " || line[0] === "+" || line[0] === "-")) {
      current.push(line);
    } // ignore "\ No newline at end of file" and anything else
  }
  return hunks;
}

// Git C-quotes a path holding a tab, quote, backslash or control character
// even with core.quotePath=false: "b/t\tx.js", with octal escapes for bytes.
function unquotePath(s) {
  if (!s.startsWith('"')) return s;
  const named = { a: 7, b: 8, t: 9, n: 10, v: 11, f: 12, r: 13 };
  const body = Buffer.from(s.slice(1, -1), "utf8");
  const bytes = [];
  for (let i = 0; i < body.length; i++) {
    if (body[i] !== 0x5c) {
      bytes.push(body[i]);
      continue;
    }
    const next = String.fromCharCode(body[i + 1]);
    if (/[0-7]/.test(next)) {
      bytes.push(parseInt(body.subarray(i + 1, i + 4).toString("ascii"), 8));
      i += 3;
    } else {
      bytes.push(named[next] ?? body[i + 1]); // \" and \\ stand for themselves
      i += 1;
    }
  }
  return Buffer.from(bytes).toString("utf8");
}

function pathFromDiffBlock(block) {
  const pathOf = (prefix) => {
    const line = block.find((l) => l.startsWith(prefix));
    // Git ends the name with a tab when it contains a space.
    return line && unquotePath(line.slice(4).replace(/\t$/, ""));
  };
  const plus = pathOf("+++ ");
  const pick = plus && plus !== "/dev/null" ? plus : pathOf("--- ");
  if (!pick || pick === "/dev/null") return null;
  return pick.replace(/^[ab]\//, "");
}

// Changed lines are looked up by line number in the raw blobs, so the patch
// must be git's plain one whatever the user's config says: no textconv or
// external diff (they rewrite the text), no blank context lines printed as ""
// (splitHunks would drop them and the numbering would drift), a/ b/ prefixes
// (pathFromDiffBlock strips them), and paths from the repo root.
const PLAIN_DIFF = [
  "--no-color",
  "--no-textconv",
  "--no-ext-diff",
  "--no-relative",
  "--src-prefix=a/",
  "--dst-prefix=b/",
  "--full-index",
  "-M",
  "-p",
];

// diffArgs: ["show", "--format=", <commit>] or ["diff", <from>, <to>].
function parseDiff(diffArgs) {
  const [cmd, ...rest] = diffArgs;
  const raw = git(["-c", "diff.suppressBlankEmpty=false", cmd, ...PLAIN_DIFF, ...rest]).toString("utf8");

  const blocks = [];
  let current = null;
  for (const line of raw.split("\n")) {
    if (line.startsWith("diff --git ")) {
      current = [line];
      blocks.push(current);
    } else if (current) {
      current.push(line);
    }
  }

  const blobId = (id) => (id && !/^0+$/.test(id) ? id : null);
  return blocks
    .map((block) => {
      const path = pathFromDiffBlock(block);
      if (!path) return null; // mode-only change or a pure rename: no lines
      const isBinary = block.some((l) => l.startsWith("Binary files ") || l.startsWith("GIT binary patch"));
      const index = block.map((l) => /^index ([0-9a-f]+)\.\.([0-9a-f]+)/.exec(l)).find(Boolean);
      return {
        path,
        isBinary,
        oldBlob: blobId(index && index[1]),
        newBlob: blobId(index && index[2]),
        hunks: isBinary ? [] : splitHunks(block),
      };
    })
    .filter(Boolean);
}

function processSourceFile(file, blobs) {
  const syntax = SYNTAX[extOf(file.path)];
  const categoriesOf = (id) => (id ? classifyText(blobs.get(id), syntax) : []);
  const oldCats = categoriesOf(file.oldBlob);
  const newCats = categoriesOf(file.newBlob);
  const added = emptyCounts();
  const removed = emptyCounts();

  for (const hunk of file.hunks) {
    let { oldStart: oldLine, newStart: newLine } = parseHunkHeader(hunk[0]);
    for (const raw of hunk.slice(1)) {
      if (raw[0] === " ") {
        oldLine++;
        newLine++;
      } else if (raw[0] === "-") {
        removed[oldCats[oldLine++ - 1]]++;
      } else {
        added[newCats[newLine++ - 1]]++;
      }
    }
  }
  return { added, removed };
}

function countPlainLines(hunks) {
  let added = 0;
  let removed = 0;
  for (const hunk of hunks) {
    for (const raw of hunk.slice(1)) {
      if (raw[0] === "+") added++;
      else if (raw[0] === "-") removed++;
    }
  }
  return { added, removed };
}

function sumCounts(c) {
  return Object.values(c).reduce((a, b) => a + b, 0);
}

function formatCounts(c) {
  return (
    `    import/package: ${c.import}\n` +
    `    комментарии:    ${c.comment}\n` +
    `    пустые строки:  ${c.empty}\n` +
    `    код:            ${c.brace + c.effective} (эффективный: ${c.effective})\n` +
    `    итого:          ${sumCounts(c)}`
  );
}

function commitDiff(commit) {
  return ["show", "--no-show-signature", "--format=", commit];
}

// Classifies every non-binary file of a diff, aggregating per-category
// added/removed counts. Shared by the per-commit report, the table and --pr.
function analyzeDiff(diffArgs, onlyFile) {
  let files = parseDiff(diffArgs).filter((f) => !f.isBinary);
  if (onlyFile) files = files.filter((f) => f.path === onlyFile);
  for (const f of files) f.category = classifyFile(f.path);

  const blobIds = new Set();
  for (const f of files) {
    if (f.category !== "source") continue;
    if (f.oldBlob) blobIds.add(f.oldBlob);
    if (f.newBlob) blobIds.add(f.newBlob);
  }
  const blobs = readBlobs([...blobIds]);

  const totals = {
    doc: { added: 0, removed: 0, files: 0 },
    build: { added: 0, removed: 0, files: 0 },
    source: { added: emptyCounts(), removed: emptyCounts(), files: 0 },
  };
  const perFile = [];

  for (const file of files) {
    const { path, category } = file;
    if (category === "source") {
      const { added, removed } = processSourceFile(file, blobs);
      for (const k of Object.keys(added)) totals.source.added[k] += added[k];
      for (const k of Object.keys(removed)) totals.source.removed[k] += removed[k];
      totals.source.files++;
      perFile.push({ path, category, added, removed });
    } else {
      const counts = countPlainLines(file.hunks);
      totals[category].added += counts.added;
      totals[category].removed += counts.removed;
      totals[category].files++;
      perFile.push({ path, category, ...counts });
    }
  }

  return { totals, perFile, fileCount: files.length };
}

function printCommitReport(commit, onlyFile) {
  const { totals, perFile, fileCount } = analyzeDiff(commitDiff(commit), onlyFile);
  if (onlyFile && fileCount === 0) {
    fail(`Файл не найден в коммите ${commit}: ${onlyFile}`);
  }

  console.log(`Коммит: ${commit}\n`);

  for (const file of perFile) {
    if (file.category === "doc") {
      console.log(`[документация] ${file.path}  +${file.added} -${file.removed}`);
    } else if (file.category === "build") {
      console.log(`[служебный]    ${file.path}  +${file.added} -${file.removed}`);
    } else {
      console.log(`[основной]     ${file.path}`);
      console.log(`  добавлено:\n${formatCounts(file.added)}`);
      console.log(`  удалено:\n${formatCounts(file.removed)}`);
    }
  }

  if (onlyFile) return; // spot-check run — skip the aggregate summary

  console.log("\n=== Итого по коммиту ===\n");
  console.log(
    `Служебные/сборочные файлы: ${totals.build.files} файл(ов), +${totals.build.added} -${totals.build.removed}`,
  );
  console.log(
    `Документация:               ${totals.doc.files} файл(ов), +${totals.doc.added} -${totals.doc.removed}`,
  );
  console.log(`\nОсновные файлы: ${totals.source.files} файл(ов)`);
  console.log("  добавлено:");
  console.log(formatCounts(totals.source.added));
  console.log("  удалено:");
  console.log(formatCounts(totals.source.removed));
}

function fmtDelta(n) {
  return n > 0 ? `+${n}` : `${n}`;
}

function listCommits(logArgs) {
  return git(["log", "--no-show-signature", "--format=%h%x09%p%x09%s", ...logArgs])
    .toString("utf8")
    .split("\n")
    .filter(Boolean)
    .map((line) => {
      const [hash, parents, subject] = line.split("\t");
      return { hash, subject, isMerge: parents.split(" ").length > 1 };
    });
}

function truncate(s, max) {
  return s.length > max ? s.slice(0, max - 1) + "…" : s;
}

const TABLE_HEADER = [
  "Коммит",
  "Файлов",
  "+/-",
  "Служебные",
  "Документация",
  "Import",
  "Комментарии",
  "Пустые",
  "Код",
  "Эффективный код",
];

function tableRow(label, { totals, fileCount }) {
  const { build, doc, source } = totals;
  const added = build.added + doc.added + sumCounts(source.added);
  const removed = build.removed + doc.removed + sumCounts(source.removed);
  const delta = (k) => source.added[k] - source.removed[k];
  return [
    label,
    String(fileCount),
    `+${added} -${removed}`,
    fmtDelta(build.added - build.removed),
    fmtDelta(doc.added - doc.removed),
    fmtDelta(delta("import")),
    fmtDelta(delta("comment")),
    fmtDelta(delta("empty")),
    fmtDelta(delta("brace") + delta("effective")),
    fmtDelta(delta("effective")),
  ];
}

// A row of null prints as a separator line.
function printTable(header, body) {
  const rows = [header, ...body.filter(Boolean)];
  const widths = header.map((_, col) => Math.max(...rows.map((r) => r[col].length)));
  const printRow = (r) => console.log("| " + r.map((c, i) => c.padEnd(widths[i])).join(" | ") + " |");
  const separator = () => console.log("| " + widths.map((w) => "-".repeat(w)).join(" | ") + " |");
  printRow(header);
  separator();
  for (const r of body) {
    if (r) printRow(r);
    else separator();
  }
}

function commitRows(commits) {
  return commits.map(({ hash, subject, isMerge }) => {
    const label = `${hash} ${truncate(subject, 40)}`;
    if (isMerge) return [label, "слияние", ...TABLE_HEADER.slice(2).map(() => "—")];
    return tableRow(label, analyzeDiff(commitDiff(hash)));
  });
}

function printCommitTable(n) {
  printTable(TABLE_HEADER, commitRows(listCommits([`-n${n}`])));
}

function printPrTable(base, head) {
  const mergeBase = git(["merge-base", base, head]).toString("utf8").trim();
  const commits = listCommits([`${mergeBase}..${head}`]);
  if (commits.length === 0) {
    console.log(`Нет коммитов в ${base}..${head}`);
    return;
  }
  console.log(`PR: ${base}..${head} — ${commits.length} коммит(ов), база ${mergeBase.slice(0, 8)}\n`);
  const total = tableRow("PR итого (net diff)", analyzeDiff(["diff", mergeBase, head]));
  printTable(TABLE_HEADER, [...commitRows(commits), null, total]);
}

function fail(message) {
  console.error(message);
  process.exit(1);
}

// `arg` is relative to the current directory, as typed in a shell; the
// repo-relative form is what classifyFile and `git show rev:path` need.
function readFileArg(arg, rev) {
  const root = git(["rev-parse", "--show-toplevel"]).toString("utf8").trim();
  const absolute = nodePath.resolve(arg);
  const path = nodePath.relative(root, absolute).split(nodePath.sep).join("/");
  try {
    const text = rev
      ? git(["show", `${rev}:${path}`], { quiet: true }).toString("utf8")
      : fs.readFileSync(absolute, "utf8");
    return { path, text };
  } catch {
    return fail(rev ? `Файла ${path} нет в ${rev}` : `Файл не найден: ${arg}`);
  }
}

function countFile(arg, rev) {
  const { path, text } = readFileArg(arg, rev);
  const category = classifyFile(path);
  const lines = text.split("\n");
  const total = lines[lines.length - 1] === "" ? lines.length - 1 : lines.length;
  if (category !== "source") return { path, category, total };

  const counts = emptyCounts();
  for (const c of classifyText(text, SYNTAX[extOf(path)])) counts[c]++;
  return { path, category, total, counts };
}

function printFileLoc(args, rev) {
  const header = ["Файл", "Всего", "Эффективный код", "Код", "Import", "Комментарии", "Пустые"];
  const columns = (c) => [c.effective, c.brace + c.effective, c.import, c.comment, c.empty];
  const body = [];
  const sum = { total: 0, ...emptyCounts() };
  for (const arg of args) {
    const r = countFile(arg, rev);
    if (!r.counts) {
      const kind = r.category === "doc" ? "документация" : "служебный";
      body.push([`${r.path} (${kind})`, String(r.total), ...columns(emptyCounts()).map(() => "—")]);
      continue;
    }
    body.push([r.path, r.total, ...columns(r.counts)].map(String));
    sum.total += r.total;
    for (const k of Object.keys(r.counts)) sum[k] += r.counts[k];
  }
  if (args.length > 1) {
    body.push(null);
    body.push(["Итого (основные файлы)", sum.total, ...columns(sum)].map(String));
  }
  printTable(header, body);
}

function defaultBase() {
  try {
    git(["rev-parse", "--verify", "--quiet", "origin/main"], { quiet: true });
    return "origin/main";
  } catch {
    return "main";
  }
}

function main() {
  const args = process.argv.slice(2);
  if (args[0] === "--table") {
    printCommitTable(Number(args[1]) || 10);
    return;
  }
  if (args[0] === "--pr") {
    printPrTable(args[1] || defaultBase(), args[2] || "HEAD");
    return;
  }
  if (args[0] === "--loc") {
    let rev = null;
    const paths = [];
    for (let i = 1; i < args.length; i++) {
      if (args[i] === "--rev") rev = args[++i];
      else paths.push(args[i]);
    }
    if (paths.length === 0) {
      fail("Укажите хотя бы один файл: --loc [--rev <rev>] <path>...");
    }
    printFileLoc(paths, rev);
    return;
  }

  let commit = "HEAD";
  let onlyFile = null;
  for (let i = 0; i < args.length; i++) {
    if (args[i] === "--file") {
      onlyFile = args[++i];
    } else {
      commit = args[i];
    }
  }
  printCommitReport(commit, onlyFile);
}

if (require.main === module) {
  main();
}

module.exports = { classifyText, classifyFile, SYNTAX };
