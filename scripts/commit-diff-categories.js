#!/usr/bin/env node
/**
 * Counts the changed lines of a commit by category:
 *   - служебные/сборочные файлы   — total only, no breakdown
 *   - документация                — total only, no breakdown
 *   - основные (исходные) файлы   — broken down into:
 *       import/package · javadoc/комментарии · пустые строки · код
 *     "код" is every line with code outside comments, imports aside; its
 *     "эффективный" part leaves out lines of brackets only (}); ) ]; …) and
 *     "try {", which carry no logic of their own.
 *     A source file is any extension with an entry in SYNTAX below.
 *   - не удалось определить       — a changed line in a source file whose
 *     category can't be told from the diff hunk alone (e.g. plain text that
 *     may be a javadoc paragraph or may be code, with the block-comment
 *     state unresolved within the visible hunk context)
 *
 * Classification works hunk-by-hunk: state (inside a block comment or not)
 * only carries within one hunk, and starts "unknown" unless the hunk opens
 * at line 1 of that side (nothing could precede it there). A line whose own
 * text can't resolve that unknown state is reported as undetermined rather
 * than guessed.
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
 * commit (for spot-checking the classifier before trusting the full report).
 * --table prints one row per commit for the last N commits (default 10,
 * newest first): file count, the usual +added/-removed, and per-category
 * added-minus-removed deltas.
 * --pr prints the same table for every commit of <base>..<head> (defaults:
 * origin/main, HEAD) plus a "PR итого" row taken from the net diff between the
 * merge base and <head> — not the sum of the rows, since a line added in one
 * commit and removed in the next is no change to the PR.
 * --loc counts a whole file (working tree, or <rev>:<path> with --rev). With
 * the full text there is no unknown state, so every line resolves. It is the
 * measure CLAUDE.md's file-size rule points to.
 */

const { execFileSync } = require("child_process");

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

// Comment and string syntax of each source language. `line` — tokens that
// comment out the rest of the line; `blocks` — [open, close] pairs; `quotes`
// — string delimiters, inside which comment tokens mean nothing (so
// "/chat/**" is code, not the start of a block comment).
const C_BLOCK = [["/*", "*/"]];
const SYNTAX = {
  ".java": { line: ["//"], blocks: C_BLOCK, quotes: ['"', "'"] },
  ".js": { line: ["//"], blocks: C_BLOCK, quotes: ['"', "'", "`"] },
  ".css": { line: [], blocks: C_BLOCK, quotes: ['"', "'"] }, // "//" is not a CSS comment: url(http://…)
  ".scss": { line: ["//"], blocks: C_BLOCK, quotes: ['"', "'"] },
  ".sql": { line: ["--"], blocks: C_BLOCK, quotes: ["'", '"'] },
  // "#" opens a comment only at a word start: $#, ${#arr} and a#b are code.
  ".sh": { line: ["#"], hashAtWordStart: true, blocks: [], quotes: ['"', "'"] },
  ".ps1": { line: ["#"], hashAtWordStart: true, blocks: [["<#", "#>"]], quotes: ['"', "'"] },
  ".bat": { lineStartCi: ["rem ", "@rem ", "::"], line: [], blocks: [], quotes: ['"'] },
  // A docstring is a string statement, so it opens only where nothing precedes
  // it on the line; x = """…""" is code.
  ".py": {
    line: ["#"],
    blocks: [['"""', '"""'], ["'''", "'''"]],
    blockAtLineStart: true,
    quotes: ['"', "'"],
  },
  ".html": { line: [], blocks: [["<!--", "-->"]], quotes: [] }, // apostrophes in text aren't quotes
};
SYNTAX[".jsx"] = SYNTAX[".js"];
SYNTAX[".ts"] = SYNTAX[".js"];
SYNTAX[".tsx"] = SYNTAX[".js"];
SYNTAX[".mjs"] = SYNTAX[".js"];
SYNTAX[".cjs"] = SYNTAX[".js"];
SYNTAX[".less"] = SYNTAX[".scss"];
SYNTAX[".bash"] = SYNTAX[".sh"];
SYNTAX[".cmd"] = SYNTAX[".bat"];
SYNTAX[".htm"] = SYNTAX[".html"];

function extOf(path) {
  const base = path.split("/").pop();
  const dot = base.lastIndexOf(".");
  return dot <= 0 ? "" : base.slice(dot).toLowerCase();
}

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

// Tested against a line's code part — comments already cut out.
function importRegexFor(ext) {
  if (ext === ".java") {
    return /^(package|import)\s+\S.*;$/;
  }
  if (SYNTAX[ext] === SYNTAX[".js"]) {
    return /^(import\b.*|export\s+(\*|\{[^}]*\})\s*from\s+['"][^'"]+['"];?|(const|let|var)\s+.+=\s*require\(.*\)\s*;?)$/;
  }
  if (ext === ".css") {
    return /^@import\b/;
  }
  if (SYNTAX[ext] === SYNTAX[".scss"]) {
    return /^@(import|use|forward)\b/;
  }
  if (SYNTAX[ext] === SYNTAX[".sh"]) {
    return /^(source|\.)\s+\S/;
  }
  if (ext === ".ps1") {
    return /^(Import-Module\b|\.\s+\S)/i;
  }
  if (ext === ".py") {
    return /^(import\s+\S|from\s+\S+\s+import\b)/;
  }
  return /$^/; // never matches
}

function languageFor(path) {
  const ext = extOf(path);
  return { syntax: SYNTAX[ext], importRe: importRegexFor(ext) };
}

function emptyCounts() {
  // "brace" + "effective" together make up the reported "код".
  return { import: 0, comment: 0, empty: 0, brace: 0, effective: 0, undetermined: 0 };
}

// Lines that only close (or open) a construct: }); ) }; ], — plus "try {".
// Code, but not effective code.
function isBraceOnly(code) {
  const compact = code.replace(/\s+/g, "");
  return /^[()[\]{};,]+$/.test(compact) || compact === "try{";
}

// Line state: "normal", "unknown" (hunk context too short to tell), or the
// close token of the block comment the line starts inside, e.g. "*/".
//
// Splits one trimmed line into its code part (strings kept verbatim) and
// reports whether any comment was on it. `bare` is the code part with string
// contents dropped — for telling whether a stray "*/" is real syntax.
function scanLine(line, state, syntax) {
  if (state === "normal" && syntax.lineStartCi) {
    const lower = line.toLowerCase();
    if (syntax.lineStartCi.some((t) => lower.startsWith(t) || lower === t.trim())) {
      return { code: "", bare: "", comment: true, state };
    }
  }
  let code = "";
  let bare = "";
  let comment = false;
  let block = state === "normal" ? null : state;
  let i = 0;
  scan: while (i < line.length) {
    if (block) {
      comment = true;
      const idx = line.indexOf(block, i);
      if (idx === -1) break;
      i = idx + block.length;
      block = null;
      continue;
    }
    for (const t of syntax.line) {
      if (line.startsWith(t, i) && (!syntax.hashAtWordStart || i === 0 || /\s/.test(line[i - 1]))) {
        comment = true;
        break scan;
      }
    }
    for (const [open, close] of syntax.blocks) {
      if (line.startsWith(open, i) && (!syntax.blockAtLineStart || code.trim() === "")) {
        comment = true;
        block = close;
        i += open.length;
        continue scan;
      }
    }
    const ch = line[i];
    if (syntax.quotes.includes(ch)) {
      let j = i + 1;
      while (j < line.length && line[j] !== ch) j += line[j] === "\\" ? 2 : 1;
      code += line.slice(i, j + 1);
      bare += ch + ch;
      i = j + 1;
      continue;
    }
    code += ch;
    bare += ch;
    i++;
  }
  return { code: code.trim(), bare, comment, state: block || "normal" };
}

function categorize(scan, importRe) {
  if (scan.code === "") return "comment";
  // JSX comment {/* … */}: the braces are only the comment's wrapper.
  if (scan.comment && scan.code.replace(/\s+/g, "") === "{}") return "comment";
  if (importRe.test(scan.code)) return "import";
  if (isBraceOnly(scan.code)) return "brace";
  return "effective";
}

function classifyKnown(trimmed, state, lang) {
  const scan = scanLine(trimmed, state, lang.syntax);
  return { category: categorize(scan, lang.importRe), nextState: scan.state };
}

// One hunk-scoped state machine, applied separately to the old-file side and
// the new-file side of a hunk (they can resolve independently). In "unknown"
// state the line is read both ways — as code and as the inside of each block
// comment kind — and only a reading the line itself supports is taken.
function classifySourceLine(trimmed, state, lang) {
  if (trimmed === "") {
    return { category: "empty", nextState: state };
  }
  if (state !== "unknown") {
    return classifyKnown(trimmed, state, lang);
  }

  const asCode = classifyKnown(trimmed, "normal", lang);
  // An import or a whole-line "//" comment is taken as outside a block comment.
  const lineComment = lang.syntax.line.some((t) => trimmed.startsWith(t));
  if (asCode.category === "import" || (asCode.category === "comment" && lineComment)) {
    return asCode;
  }
  const asComment = lang.syntax.blocks.map(([, close]) => ({
    close,
    ...classifyKnown(trimmed, close, lang),
  }));
  const readings = [asCode, ...asComment];
  if (readings.every((r) => r.category === asCode.category)) {
    const sameState = readings.every((r) => r.nextState === asCode.nextState);
    return { category: asCode.category, nextState: sameState ? asCode.nextState : "unknown" };
  }

  const codeScan = scanLine(trimmed, "normal", lang.syntax);
  // A close token as bare syntax can't be code — the line ends a block comment.
  const closed = asComment.find((r) => codeScan.bare.includes(r.close));
  if (closed) return closed;
  // Javadoc-style continuation line, e.g. " * some paragraph text".
  const cBlock = asComment.find((r) => r.close === "*/");
  if (cBlock && trimmed.startsWith("*")) return cBlock;
  return { category: "undetermined", nextState: "unknown" };
}

function parseHunkHeader(line) {
  const m = /^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@/.exec(line);
  if (!m) return null;
  return { oldStart: Number(m[1]), newStart: Number(m[3]) };
}

function processSourceFile(path, hunks) {
  const lang = languageFor(path);
  const added = emptyCounts();
  const removed = emptyCounts();

  for (const hunk of hunks) {
    const header = parseHunkHeader(hunk[0]);
    let oldState = header.oldStart === 1 ? "normal" : "unknown";
    let newState = header.newStart === 1 ? "normal" : "unknown";

    for (const raw of hunk.slice(1)) {
      const marker = raw[0];
      const text = raw.slice(1);
      const trimmed = text.trim();
      if (marker === " ") {
        oldState = classifySourceLine(trimmed, oldState, lang).nextState;
        newState = classifySourceLine(trimmed, newState, lang).nextState;
      } else if (marker === "-") {
        const r = classifySourceLine(trimmed, oldState, lang);
        oldState = r.nextState;
        removed[r.category]++;
      } else if (marker === "+") {
        const r = classifySourceLine(trimmed, newState, lang);
        newState = r.nextState;
        added[r.category]++;
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

function pathFromDiffBlock(block) {
  const plusLine = block.find((l) => l.startsWith("+++ "));
  const minusLine = block.find((l) => l.startsWith("--- "));
  const fromPlusPlus = plusLine && plusLine.slice(4).trim();
  const fromMinusMinus = minusLine && minusLine.slice(4).trim();
  const pick = fromPlusPlus && fromPlusPlus !== "/dev/null" ? fromPlusPlus : fromMinusMinus;
  if (!pick || pick === "/dev/null") return null;
  return pick.replace(/^[ab]\//, "");
}

function git(args) {
  return execFileSync("git", ["-c", "core.quotePath=false", ...args], {
    maxBuffer: 1024 * 1024 * 256,
  }).toString("utf8");
}

// diffArgs: ["show", "--format=", <commit>] or ["diff", <from>, <to>].
function parseDiff(diffArgs) {
  const [cmd, ...rest] = diffArgs;
  const raw = git([cmd, "--no-color", "-p", "-M", ...rest]);

  const lines = raw.split("\n");
  const blocks = [];
  let current = null;
  for (const line of lines) {
    if (line.startsWith("diff --git ")) {
      current = [line];
      blocks.push(current);
    } else if (current) {
      current.push(line);
    }
  }

  return blocks
    .map((block) => {
      const path = pathFromDiffBlock(block);
      if (!path) return null;
      const isBinary = block.some(
        (l) => l.startsWith("Binary files ") || l.startsWith("GIT binary patch"),
      );
      const hunks = isBinary ? [] : splitHunks(block);
      return { path, isBinary, hunks };
    })
    .filter(Boolean);
}

function formatCounts(c) {
  const total = sumCounts(c);
  return (
    `    import/package: ${c.import}\n` +
    `    комментарии:    ${c.comment}\n` +
    `    пустые строки:  ${c.empty}\n` +
    `    код:            ${c.brace + c.effective} (эффективный: ${c.effective})\n` +
    `    не определено:  ${c.undetermined}\n` +
    `    итого:          ${total}`
  );
}

function commitDiff(commit) {
  return ["show", "--format=", commit];
}

// Classifies every non-binary file of a diff, aggregating per-category
// added/removed counts. Shared by the per-commit report, the table and --pr.
function analyzeDiff(diffArgs, onlyFile) {
  let files = parseDiff(diffArgs).filter((f) => !f.isBinary);
  if (onlyFile) files = files.filter((f) => f.path === onlyFile);

  const totals = {
    doc: { added: 0, removed: 0, files: 0 },
    build: { added: 0, removed: 0, files: 0 },
    source: { added: emptyCounts(), removed: emptyCounts(), files: 0 },
  };
  const perFile = [];

  for (const file of files) {
    const category = classifyFile(file.path);
    if (category === "doc") {
      const counts = countPlainLines(file.hunks);
      totals.doc.added += counts.added;
      totals.doc.removed += counts.removed;
      totals.doc.files++;
      perFile.push({ path: file.path, category, ...counts });
    } else if (category === "build") {
      const counts = countPlainLines(file.hunks);
      totals.build.added += counts.added;
      totals.build.removed += counts.removed;
      totals.build.files++;
      perFile.push({ path: file.path, category, ...counts });
    } else {
      const { added, removed } = processSourceFile(file.path, file.hunks);
      for (const k of Object.keys(added)) totals.source.added[k] += added[k];
      for (const k of Object.keys(removed)) totals.source.removed[k] += removed[k];
      totals.source.files++;
      perFile.push({ path: file.path, category, added, removed });
    }
  }

  return { totals, perFile, fileCount: files.length };
}

function printCommitReport(commit, onlyFile) {
  const { totals, perFile, fileCount } = analyzeDiff(commitDiff(commit), onlyFile);
  if (onlyFile && fileCount === 0) {
    console.error(`Файл не найден в коммите ${commit}: ${onlyFile}`);
    process.exit(1);
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
  return git(["log", "--format=%h %s", ...logArgs])
    .split("\n")
    .filter(Boolean)
    .map((line) => {
      const sp = line.indexOf(" ");
      return { hash: line.slice(0, sp), subject: line.slice(sp + 1) };
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
  "Не определено",
];

function sumCounts(c) {
  return Object.values(c).reduce((a, b) => a + b, 0);
}

function tableRow(label, { totals, fileCount }) {
  const { build, doc, source } = totals;
  const added = build.added + doc.added + sumCounts(source.added);
  const removed = build.removed + doc.removed + sumCounts(source.removed);
  const delta = (k) => fmtDelta(source.added[k] - source.removed[k]);
  return [
    label,
    String(fileCount),
    `+${added} -${removed}`,
    fmtDelta(build.added - build.removed),
    fmtDelta(doc.added - doc.removed),
    delta("import"),
    delta("comment"),
    delta("empty"),
    fmtDelta(source.added.brace + source.added.effective - source.removed.brace - source.removed.effective),
    delta("effective"),
    delta("undetermined"),
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
  return commits.map(({ hash, subject }) =>
    tableRow(`${hash} ${truncate(subject, 40)}`, analyzeDiff(commitDiff(hash))),
  );
}

function printCommitTable(n) {
  printTable(TABLE_HEADER, commitRows(listCommits([`-n${n}`])));
}

function printPrTable(base, head) {
  const mergeBase = git(["merge-base", base, head]).trim();
  const commits = listCommits([`${mergeBase}..${head}`]);
  if (commits.length === 0) {
    console.log(`Нет коммитов в ${base}..${head}`);
    return;
  }
  console.log(`PR: ${base}..${head} — ${commits.length} коммит(ов), база ${mergeBase.slice(0, 8)}\n`);
  const total = tableRow("PR итого (net diff)", analyzeDiff(["diff", mergeBase, head]));
  printTable(TABLE_HEADER, [...commitRows(commits), null, total]);
}

function readFileAt(path, rev) {
  if (rev) return git(["show", `${rev}:${path}`]);
  return require("fs").readFileSync(path, "utf8");
}

// Whole-file counterpart of processSourceFile: the file starts at line 1, so
// the state is known from the first line on and nothing is undetermined.
function countFileLines(path, rev) {
  const text = readFileAt(path, rev);
  const lines = text.split("\n");
  if (lines.length > 0 && lines[lines.length - 1] === "") lines.pop(); // trailing newline
  const category = classifyFile(path);
  if (category !== "source") return { path, category, total: lines.length };

  const lang = languageFor(path);
  const counts = emptyCounts();
  let state = "normal";
  for (const line of lines) {
    const r = classifySourceLine(line.trim(), state, lang);
    state = r.nextState;
    counts[r.category]++;
  }
  return { path, category, total: lines.length, counts };
}

function printFileLoc(paths, rev) {
  const header = ["Файл", "Всего", "Эффективный код", "Код", "Import", "Комментарии", "Пустые"];
  const columns = (c) => [c.effective, c.brace + c.effective, c.import, c.comment, c.empty];
  const body = [];
  const sum = { total: 0, ...emptyCounts() };
  for (const path of paths) {
    const r = countFileLines(path, rev);
    if (!r.counts) {
      const kind = r.category === "doc" ? "документация" : "служебный";
      body.push([`${path} (${kind})`, String(r.total), ...columns(emptyCounts()).map(() => "—")]);
      continue;
    }
    body.push([path, r.total, ...columns(r.counts)].map(String));
    sum.total += r.total;
    for (const k of Object.keys(r.counts)) sum[k] += r.counts[k];
  }
  if (paths.length > 1) {
    body.push(null);
    body.push(["Итого (основные файлы)", sum.total, ...columns(sum)].map(String));
  }
  printTable(header, body);
}

function defaultBase() {
  try {
    git(["rev-parse", "--verify", "--quiet", "origin/main"]);
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
      console.error("Укажите хотя бы один файл: --loc [--rev <rev>] <path>...");
      process.exit(1);
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

main();
