import shortRev from '@/components/common/git/shortRev';

// Best-effort Markdown to Jira wiki markup converter (line-based, no AST
// parser) - mirrors the classic wiki syntax: h1./h2., *bold*, _italic_,
// -strike-, monospace via double braces, {code:lang}, links via pipe,
// images via bang, bq./{quote}, table pipes, list markers *`/`#`.
// Inspired by https://github.com/jadujoel/markdown-to-jira.

const CODE_LANG_ALIASES = {
  js: 'javascript',
  jsx: 'javascript',
  mjs: 'javascript',
  cjs: 'javascript',
  ts: 'typescript',
  tsx: 'typescript',
  py: 'python',
  rb: 'ruby',
  sh: 'bash',
  shell: 'bash',
  zsh: 'bash',
  yml: 'yaml',
  md: 'none',
  txt: 'none',
};

function normalizeLang(lang) {
  const key = (lang || '').trim().toLowerCase();
  if (!key) return '';
  return CODE_LANG_ALIASES[key] || key;
}

// Internal KB links (see docLinkParsing.js parseFileLink/parseCommitLink/parseDocId
// for the canonical, DOM-based parser used elsewhere): these point at in-app routes,
// not real hyperlinks, so a Jira `[text|url]` link would be dead once pasted
// into an issue. File links become "name (full/path)" ("name (full/path @ rev)"
// for a file as of a commit); a commit link becomes the short hash in monospace
// when its label is the hash, and "label ({{hash}})" otherwise — the same rule as
// the document export (DocumentLinkRewriter.flattenCommitLinks); doc links become
// plain text (just the link label).
const FILE_LINK_RE = /^\/files\?path=([^&#\s]+)/;
// Only a hex hash counts, as in the document export: a link naming a branch is not
// a stored commit link, and it stays an ordinary (dead) link either way.
const LINK_REV_RE = /[?&]rev=([0-9a-fA-F]{4,64})(?=[&#]|$)/;
const COMMIT_LINK_RE = /^\/files\/?\?/;
const DOC_LINK_RE = /(?:^|[?&])doc=\d+(?:&|$|#)/;

function isRelativeInternalUrl(url) {
  return !/^[a-z][a-z0-9+.-]*:\/\//i.test(url) && !url.startsWith('//');
}

const HEADING_RE = /^ {0,3}(#{1,6})\s+(.*)$/;
// CommonMark fence, as the backend's MarkdownSections reads it: indent, optional list-item
// markers the fence may follow, 3+ backticks or tildes, info string.
const FENCE_RE = /^( *)((?:(?:[-*+]|\d{1,9}[.)])[ \t]+)*)(`{3,}|~{3,})(.*)$/;

// After a backtick fence the info string may not hold a backtick: ```js``` text is inline code.
function isFenceOpener(match) {
  return match[3][0] === '~' || !match[4].includes('`');
}

function closesFence(line, fence, fenceColumn) {
  const m = line.match(FENCE_RE);
  return Boolean(
    m && !m[2] && m[1].length <= fenceColumn + 3 && m[3][0] === fence[0] && m[3].length >= fence.length && !m[4].trim(),
  );
}
const HR_RE = /^ {0,3}([-*_])(?:\s*\1){2,}\s*$/;
const UL_RE = /^(\s*)[-*+]\s+(.*)$/;
const OL_RE = /^(\s*)\d+[.)]\s+(.*)$/;
const QUOTE_RE = /^\s*>\s?(.*)$/;
const TABLE_ROW_RE = /^\s*\|(.+)\|\s*$/;

function splitTableRow(line) {
  let t = line.trim();
  if (t.startsWith('|')) t = t.slice(1);
  if (t.endsWith('|')) t = t.slice(0, -1);
  return t.split('|');
}

function isTableSeparatorRow(line) {
  const cells = splitTableRow(line);
  return cells.length > 0 && cells.every((c) => /^:?-+:?$/.test(c.trim()));
}

// Marker letters wrapping a stashed-token index. Formatted spans (code,
// links, bold, ...) are replaced with `JMDTOK<n>KOTDMJ` as they're produced,
// so a later pass (e.g. italic) can't re-match markers already converted
// (like the single asterisk left over from **bold**). Plain uppercase ASCII
// keeps this immune to whitespace/unicode normalization surprises.
const TOKEN_OPEN = 'JMDTOK';
const TOKEN_CLOSE = 'KOTDMJ';
const TOKEN_RE = new RegExp(`${TOKEN_OPEN}(\\d+)${TOKEN_CLOSE}`, 'g');

// Characters that are unsafe to leave literal inside {{monospace}} content:
// - `{`/`}` are ambiguous against the `{{`/`}}` delimiter itself, e.g. code
//   containing `{"a": 1}` would produce `{{{"a": 1}}}`, where the first/last
//   brace of the code reads as part of the delimiter instead of content.
// - `(`/`)` can trigger Jira's emoticon parsing (e.g. `(.` inside a value
//   like `urn:li:dataset:(...)`), turning part of the code into a smiley.
// `\` itself must be escaped too (and in the same pass as the rest, not a
// separate one done first or after) — otherwise a code span containing a
// literal backslash right before one of these characters, e.g. `\{`, ends
// up as `\{` -> `\\{`, which an escape-aware reader unescapes back down to
// a bare, unescaped `{`, undoing the very escaping this function exists for.
function escapeJiraBraces(text) {
  return text.replace(/[\\{}()]/g, '\\$&');
}

// Converts inline spans (code, images, links, bold, strike, italic) within a
// single line.
function convertInline(text) {
  const tokens = [];
  const stash = (finalText) => {
    tokens.push(finalText);
    return `${TOKEN_OPEN}${tokens.length - 1}${TOKEN_CLOSE}`;
  };

  let out = text;
  out = out.replace(/`([^`]+)`/g, (_, code) => stash(`{{${escapeJiraBraces(code)}}}`));
  out = out.replace(/!\[([^\]]*)\]\(([^)\s]+)(?:\s+"[^"]*")?\)/g, (_, alt, url) =>
    stash(alt ? `!${url}|alt=${alt}!` : `!${url}!`),
  );
  out = out.replace(/\[([^\]]+)\]\(([^)\s]+)(?:\s+"[^"]*")?\)/g, (_, label, url) => {
    const fileMatch = url.match(FILE_LINK_RE);
    if (fileMatch) {
      const filePath = decodeURIComponent(fileMatch[1]);
      const fileName = filePath.split('/').pop();
      const rev = url.match(LINK_REV_RE);
      return stash(
        rev ? `${fileName} (${filePath} @ ${shortRev(decodeURIComponent(rev[1]))})` : `${fileName} (${filePath})`,
      );
    }
    const commitRev = COMMIT_LINK_RE.test(url) && !/[?&]path=/.test(url) ? url.match(LINK_REV_RE) : null;
    if (commitRev) {
      const rev = decodeURIComponent(commitRev[1]);
      const hash = stash(`{{${escapeJiraBraces(shortRev(rev))}}}`);
      // The label arrives with inline code already stashed (`\`0123456\`` is a token by
      // now), so "is the label the hash" is asked of the tokens' own text.
      const bare = label
        .replace(TOKEN_RE, (_, i) => tokens[Number(i)])
        .replace(/[{}\\`]/g, '')
        .trim()
        .toLowerCase();
      return bare.length >= 4 && rev.toLowerCase().startsWith(bare) ? hash : `${label} (${hash})`;
    }
    if (isRelativeInternalUrl(url) && DOC_LINK_RE.test(url)) {
      return stash(label);
    }
    return stash(`[${label}|${url}]`);
  });
  out = out.replace(/\*\*([^*]+)\*\*/g, (_, inner) => stash(`*${inner}*`));
  out = out.replace(/__([^_]+)__/g, (_, inner) => stash(`*${inner}*`));
  out = out.replace(/~~([^~]+)~~/g, '-$1-');
  out = out.replace(/\*([^*\n]+)\*/g, '_$1_');
  out = out.replace(/_([^_\n]+)_/g, '_$1_');

  // Restore stashed tokens; repeat since a stashed span (e.g. bold) can itself
  // contain another token (e.g. inline code nested inside it).
  let prev;
  do {
    prev = out;
    out = out.replace(TOKEN_RE, (_, idx) => tokens[Number(idx)]);
  } while (out !== prev && out.includes(TOKEN_OPEN));
  return out;
}

/** Converts a Markdown document to Jira wiki markup, ready to paste into an issue/comment. */
export function markdownToJira(markdown) {
  if (!markdown) return '';
  const lines = markdown.replace(/\r\n/g, '\n').split('\n');
  const out = [];
  let i = 0;

  while (i < lines.length) {
    const line = lines[i];

    const fenceMatch = line.match(FENCE_RE);
    if (fenceMatch && (fenceMatch[1].length <= 3 || fenceMatch[2]) && isFenceOpener(fenceMatch)) {
      const fence = fenceMatch[3];
      const lang = normalizeLang(fenceMatch[4].trim().split(/\s+/)[0] || '');
      const fenceColumn = fenceMatch[1].length + fenceMatch[2].length;
      const body = [];
      i += 1;
      while (i < lines.length && !closesFence(lines[i], fence, fenceColumn)) {
        body.push(lines[i]);
        i += 1;
      }
      i += 1; // skip closing fence
      out.push(lang ? `{code:${lang}}` : '{code}');
      out.push(...body);
      out.push('{code}');
      continue;
    }

    if (TABLE_ROW_RE.test(line) && i + 1 < lines.length && isTableSeparatorRow(lines[i + 1])) {
      const headerCells = splitTableRow(line).map((c) => convertInline(c.trim()));
      out.push(`||${headerCells.join('||')}||`);
      i += 2; // header + separator
      while (i < lines.length && TABLE_ROW_RE.test(lines[i])) {
        const cells = splitTableRow(lines[i]).map((c) => convertInline(c.trim()));
        out.push(`|${cells.join('|')}|`);
        i += 1;
      }
      continue;
    }

    if (QUOTE_RE.test(line)) {
      const body = [];
      while (i < lines.length && QUOTE_RE.test(lines[i])) {
        body.push(convertInline(lines[i].replace(QUOTE_RE, '$1')));
        i += 1;
      }
      out.push(body.length === 1 ? `bq. ${body[0]}` : `{quote}\n${body.join('\n')}\n{quote}`);
      continue;
    }

    const headingMatch = line.match(HEADING_RE);
    if (headingMatch) {
      out.push(`h${headingMatch[1].length}. ${convertInline(headingMatch[2])}`);
      i += 1;
      continue;
    }

    if (HR_RE.test(line)) {
      out.push('----');
      i += 1;
      continue;
    }

    const ulMatch = line.match(UL_RE);
    if (ulMatch) {
      const depth = Math.floor(ulMatch[1].length / 2) + 1;
      out.push(`${'*'.repeat(depth)} ${convertInline(ulMatch[2])}`);
      i += 1;
      continue;
    }

    const olMatch = line.match(OL_RE);
    if (olMatch) {
      const depth = Math.floor(olMatch[1].length / 2) + 1;
      out.push(`${'#'.repeat(depth)} ${convertInline(olMatch[2])}`);
      i += 1;
      continue;
    }

    out.push(convertInline(line));
    i += 1;
  }

  return out.join('\n');
}
