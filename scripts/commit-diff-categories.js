#!/usr/bin/env node
/**
 * Counts the changed lines of a commit by category:
 *   - служебные/сборочные файлы   — total only, no breakdown
 *   - документация                — total only, no breakdown
 *   - основные (исходные) файлы   — broken down into:
 *       import/package · javadoc/комментарии · пустые строки · код
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
 *
 * <commit> defaults to HEAD. --file restricts the report to one path in the
 * commit (for spot-checking the classifier before trusting the full report).
 * --table prints one row per commit for the last N commits (default 10,
 * newest first): file count, the usual +added/-removed, and per-category
 * added-minus-removed deltas.
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
  ".yml",
  ".yaml",
  ".lock",
  ".properties",
  ".toml",
  ".cfg",
  ".ini",
]);

const SOURCE_EXTENSIONS = new Set([
  ".java",
  ".js",
  ".jsx",
  ".ts",
  ".tsx",
  ".css",
  ".scss",
  ".less",
]);

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
  if (SOURCE_EXTENSIONS.has(ext)) {
    return "source";
  }
  return "build"; // anything unrecognized (assets, fixtures, etc.) — general/service bucket
}

function importRegexFor(ext) {
  if (ext === ".java") {
    return /^(package|import)\s+\S.*;$/;
  }
  if ([".js", ".jsx", ".ts", ".tsx"].includes(ext)) {
    return /^(import\b.*|export\s+(\*|\{[^}]*\})\s*from\s+['"][^'"]+['"];?|(const|let|var)\s+.+=\s*require\(.*\)\s*;?)$/;
  }
  if ([".css", ".scss", ".less"].includes(ext)) {
    return /^@import\b/;
  }
  return /$^/; // never matches
}

function emptyCounts() {
  return { import: 0, comment: 0, empty: 0, code: 0, undetermined: 0 };
}

// One hunk-scoped state machine, applied separately to the old-file side and
// the new-file side of a hunk (they can resolve independently).
function classifyLine(trimmed, state) {
  if (trimmed === "") {
    return { category: "empty", nextState: state };
  }
  if (state === "inBlockComment") {
    const closeIdx = trimmed.lastIndexOf("*/");
    const reopenIdx = trimmed.lastIndexOf("/*");
    const nextState = closeIdx !== -1 && closeIdx > reopenIdx ? "normal" : "inBlockComment";
    return { category: "comment", nextState };
  }
  return null; // caller handles "normal"/"unknown" with the import regex in scope
}

function classifySourceLine(trimmed, state, importRe) {
  const blockResult = classifyLine(trimmed, state);
  if (blockResult) return blockResult;

  if (importRe.test(trimmed)) {
    return { category: "import", nextState: state === "unknown" ? "normal" : state };
  }
  if (trimmed.startsWith("//")) {
    return { category: "comment", nextState: state === "unknown" ? "normal" : state };
  }
  const openIdx = trimmed.indexOf("/*");
  const closeIdx = trimmed.indexOf("*/");
  if (openIdx !== -1) {
    const closesAfterOpen = closeIdx !== -1 && closeIdx > openIdx;
    return { category: "comment", nextState: closesAfterOpen ? "normal" : "inBlockComment" };
  }
  if (closeIdx !== -1) {
    // A lone "*/" with no opening on this line: state must have been an
    // (unseen) block comment all along — resolves "unknown" retroactively.
    return { category: "comment", nextState: "normal" };
  }
  if (state === "unknown") {
    if (trimmed.startsWith("*")) {
      // Javadoc-style continuation line, e.g. " * some paragraph text".
      return { category: "comment", nextState: "inBlockComment" };
    }
    return { category: "undetermined", nextState: "unknown" };
  }
  return { category: "code", nextState: "normal" };
}

function parseHunkHeader(line) {
  const m = /^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@/.exec(line);
  if (!m) return null;
  return { oldStart: Number(m[1]), newStart: Number(m[3]) };
}

function processSourceFile(path, hunks) {
  const ext = extOf(path);
  const importRe = importRegexFor(ext);
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
        oldState = classifySourceLine(trimmed, oldState, importRe).nextState;
        newState = classifySourceLine(trimmed, newState, importRe).nextState;
      } else if (marker === "-") {
        const r = classifySourceLine(trimmed, oldState, importRe);
        oldState = r.nextState;
        removed[r.category]++;
      } else if (marker === "+") {
        const r = classifySourceLine(trimmed, newState, importRe);
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

function parseCommitDiff(commit) {
  const raw = execFileSync(
    "git",
    ["-c", "core.quotePath=false", "show", "--no-color", "-p", "--format=", "-M", commit],
    { maxBuffer: 1024 * 1024 * 256 },
  ).toString("utf8");

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
  const total = c.import + c.comment + c.empty + c.code + c.undetermined;
  return (
    `    import/package: ${c.import}\n` +
    `    комментарии:    ${c.comment}\n` +
    `    пустые строки:  ${c.empty}\n` +
    `    код:            ${c.code}\n` +
    `    не определено:  ${c.undetermined}\n` +
    `    итого:          ${total}`
  );
}

// Classifies every non-binary file of a commit, aggregating per-category
// added/removed counts. Shared by the per-commit report and the table mode.
function analyzeCommit(commit, onlyFile) {
  let files = parseCommitDiff(commit).filter((f) => !f.isBinary);
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
  const { totals, perFile, fileCount } = analyzeCommit(commit, onlyFile);
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

function getLastCommits(n) {
  return execFileSync("git", ["log", `-n${n}`, "--format=%h %s"], { encoding: "utf8" })
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

function printCommitTable(n) {
  const commits = getLastCommits(n);
  const header = [
    "Коммит",
    "Файлов",
    "+/-",
    "Служебные",
    "Документация",
    "Import",
    "Комментарии",
    "Пустые",
    "Код",
    "Не определено",
  ];
  const rows = [header];

  for (const { hash, subject } of commits) {
    const { totals, fileCount } = analyzeCommit(hash);
    const totalAdded = totals.build.added + totals.doc.added + totals.source.added.import
      + totals.source.added.comment + totals.source.added.empty + totals.source.added.code
      + totals.source.added.undetermined;
    const totalRemoved = totals.build.removed + totals.doc.removed + totals.source.removed.import
      + totals.source.removed.comment + totals.source.removed.empty + totals.source.removed.code
      + totals.source.removed.undetermined;

    rows.push([
      `${hash} ${truncate(subject, 40)}`,
      String(fileCount),
      `+${totalAdded} -${totalRemoved}`,
      fmtDelta(totals.build.added - totals.build.removed),
      fmtDelta(totals.doc.added - totals.doc.removed),
      fmtDelta(totals.source.added.import - totals.source.removed.import),
      fmtDelta(totals.source.added.comment - totals.source.removed.comment),
      fmtDelta(totals.source.added.empty - totals.source.removed.empty),
      fmtDelta(totals.source.added.code - totals.source.removed.code),
      fmtDelta(totals.source.added.undetermined - totals.source.removed.undetermined),
    ]);
  }

  const widths = header.map((_, col) => Math.max(...rows.map((r) => r[col].length)));
  const printRow = (r) => console.log("| " + r.map((c, i) => c.padEnd(widths[i])).join(" | ") + " |");
  printRow(rows[0]);
  console.log("| " + widths.map((w) => "-".repeat(w)).join(" | ") + " |");
  for (const r of rows.slice(1)) printRow(r);
}

function main() {
  const args = process.argv.slice(2);
  if (args[0] === "--table") {
    printCommitTable(Number(args[1]) || 10);
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
