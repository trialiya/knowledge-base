import React, { useState, useEffect, useRef, useCallback } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import rehypeSlug from 'rehype-slug';
import {
  IconBold,
  IconItalic,
  IconCode,
  IconLink,
  IconH1,
  IconList,
  IconQuote,
  IconEye,
  IconEyeOff,
  IconExpand,
  IconCopy,
  IconCheck,
  IconStrike,
  IconCodeBlock,
  IconOrderedList,
  IconChecklist,
  IconHr,
  IconImage,
  IconTable,
  IconHistory,
} from './icons';
import DocLinkTooltip from './DocLinkTooltip';
import AtMentionDropdown from './AtMentionDropdown';
import useAtMention from './useAtMention';
import CodeBlock from '../common/CodeBlock';
import { setEditorDirty } from './editorDirtyStore';

// remark / rehype plugin arrays — stable references so ReactMarkdown doesn't
// rebuild its processor on every render. rehypeSlug adds GitHub-style `id`s to
// headings (same slugger that produced the `#обзор` anchors in tables of
// contents), so in-document anchor links resolve. DocLinkTooltip handles the
// click → scroll-into-view (see its hash-link branch).
const REMARK_PLUGINS = [remarkGfm];
const REHYPE_PLUGINS = [rehypeSlug];

// ─── Markdown components factory ──────────────────────────────────────────────
// Returns a ReactMarkdown `components` map that intercepts /?doc=N links.
// Called outside the component so the object reference is stable per (tree, onNavigate) pair.

function getMarkdownComponents(tree, onNavigate) {
  return {
    a: ({ href, children, ...props }) => (
      <DocLinkTooltip href={href} tree={tree} onNavigate={onNavigate} {...props}>
        {children}
      </DocLinkTooltip>
    ),
    code({ inline, className, children, ...props }) {
      const raw = String(children).replace(/\n$/, '');
      const isBlock = !inline && (raw.includes('\n') || /language-(\w+)/.test(className || ''));

      if (!isBlock) {
        return (
          <code className={className} {...props}>
            {children}
          </code>
        );
      }
      return (
        <CodeBlock code={raw} className={className} {...props}>
          {raw}
        </CodeBlock>
      );
    },
  };
}

// ─── Toolbar button ───────────────────────────────────────────────────────────

const ToolbarBtn = ({ icon, title, onClick, disabled, active }) => (
  <button
    className={`md-toolbar__btn${active ? ' md-toolbar__btn--active' : ''}`}
    title={title}
    disabled={disabled}
    onMouseDown={(e) => {
      e.preventDefault();
      if (!disabled) onClick();
    }}
  >
    {icon}
  </button>
);

// ─── Insert helpers ───────────────────────────────────────────────────────────

function wrapSelection(textarea, before, after = before) {
  const { selectionStart: s, selectionEnd: e, value } = textarea;
  const selected = value.slice(s, e) || 'текст';
  const newVal = value.slice(0, s) + before + selected + after + value.slice(e);
  return { newVal, from: s + before.length, to: s + before.length + selected.length };
}

function prependLine(textarea, prefix) {
  const { selectionStart: s, value } = textarea;
  const lineStart = value.lastIndexOf('\n', s - 1) + 1;
  const already = value.slice(lineStart).startsWith(prefix);
  const newVal = already
    ? value.slice(0, lineStart) + value.slice(lineStart + prefix.length)
    : value.slice(0, lineStart) + prefix + value.slice(lineStart);
  return {
    newVal,
    from: s + (already ? -prefix.length : prefix.length),
    to: s + (already ? -prefix.length : prefix.length),
  };
}

/**
 * Inserts a multi-line `block` at the cursor, guaranteeing it sits on its own
 * line(s) (adds surrounding newlines only when needed). If `placeholder` is a
 * substring of the block, the caret selects it so the user can type over it;
 * otherwise the caret lands right after the inserted block.
 */
function insertBlock(textarea, block, placeholder) {
  const { selectionStart: s, selectionEnd: e, value } = textarea;
  const before = value.slice(0, s);
  const after = value.slice(e);
  const lead = before && !before.endsWith('\n') ? '\n' : '';
  const trail = after && !after.startsWith('\n') ? '\n' : '';
  const insert = lead + block + trail;
  const newVal = before + insert + after;

  if (placeholder && block.includes(placeholder)) {
    const from = before.length + lead.length + block.indexOf(placeholder);
    return { newVal, from, to: from + placeholder.length };
  }
  const caret = before.length + insert.length;
  return { newVal, from: caret, to: caret };
}

function insertCodeBlock(textarea) {
  const { selectionStart: s, selectionEnd: e, value } = textarea;
  const selected = value.slice(s, e);
  const body = selected || 'код';
  return insertBlock(textarea, '```\n' + body + '\n```', selected ? null : 'код');
}

function insertTable(textarea) {
  const table = ['| Колонка 1 | Колонка 2 |', '| --- | --- |', '| Ячейка | Ячейка |'].join('\n');
  return insertBlock(textarea, table, 'Колонка 1');
}

// ─── Component ────────────────────────────────────────────────────────────────

/**
 * props:
 *   value       — markdown string
 *   placeholder — placeholder text for textarea
 *   onSave      — async (val: string) => void
 *   previewOnly — if true, renders only the preview pane without toolbar or editor controls
 *   tree        — KB tree array (for DocLinkTooltip instant lookup)
 *   onNavigate  — (node) => void (for DocLinkTooltip "Открыть" button)
 *   onExpand    — optional () => void; if set, shows an "expand" button in the toolbar right group
 */
const MarkdownEditor = ({
  value,
  placeholder = '# Markdown...',
  onSave,
  previewOnly = false,
  tree = [],
  onNavigate,
  onExpand,
  onHistory,
}) => {
  const [val, setVal] = useState(value);
  const [dirty, setDirty] = useState(false);
  const [preview, setPreview] = useState(false);
  const [saving, setSaving] = useState(false);
  const [copied, setCopied] = useState(false);
  const textareaRef = useRef(null);
  const copyTimerRef = useRef(null);
  // Stable per-instance id for the shared dirty registry.
  const dirtyIdRef = useRef(`md-${Math.random().toString(36).slice(2)}`);

  useEffect(() => {
    setVal(value);
    setDirty(false);
  }, [value]);

  // Publish the dirty flag to the shared store so the navigation guard in
  // useKnowledgeBase can warn before this editor is unmounted on doc switch.
  // previewOnly instances never edit, so they must never mark the store dirty.
  useEffect(() => {
    if (previewOnly) return;
    setEditorDirty(dirtyIdRef.current, dirty);
  }, [dirty, previewOnly]);

  // Always clear this instance's mark when the editable editor goes away (doc
  // switch, tab change, fullscreen close) so a stale `true` can't block later nav.
  useEffect(() => {
    if (previewOnly) return undefined;
    const id = dirtyIdRef.current;
    return () => setEditorDirty(id, false);
  }, [previewOnly]);

  // Cleanup the "copied" reset timer on unmount.
  useEffect(() => () => clearTimeout(copyTimerRef.current), []);

  // ── @mention ──────────────────────────────────────────────────────────────

  const mention = useAtMention(textareaRef, val, (newVal, newCursor) => {
    setVal(newVal);
    setDirty(true);
    requestAnimationFrame(() => {
      const ta = textareaRef.current;
      if (ta) ta.setSelectionRange(newCursor, newCursor);
    });
  });

  // ── Toolbar ───────────────────────────────────────────────────────────────

  const applyTransform = useCallback(({ newVal, from, to }) => {
    setVal(newVal);
    setDirty(true);
    requestAnimationFrame(() => {
      const ta = textareaRef.current;
      if (!ta) return;
      ta.focus();
      ta.setSelectionRange(from, to);
    });
  }, []);

  // Copy the full markdown source to the clipboard.
  const handleCopyAll = useCallback(async () => {
    try {
      await navigator.clipboard.writeText(val);
      setCopied(true);
      clearTimeout(copyTimerRef.current);
      copyTimerRef.current = setTimeout(() => setCopied(false), 1500);
    } catch {
      /* clipboard API недоступен в insecure context */
    }
  }, [val]);

  // Grouped so the toolbar can render separators between logical clusters.
  const toolbarGroups = [
    // Inline text formatting
    [
      { icon: <IconH1 />, title: 'Заголовок', action: () => applyTransform(prependLine(textareaRef.current, '## ')) },
      { icon: <IconBold />, title: 'Жирный', action: () => applyTransform(wrapSelection(textareaRef.current, '**')) },
      { icon: <IconItalic />, title: 'Курсив', action: () => applyTransform(wrapSelection(textareaRef.current, '_')) },
      {
        icon: <IconStrike />,
        title: 'Зачёркнутый',
        action: () => applyTransform(wrapSelection(textareaRef.current, '~~')),
      },
      {
        icon: <IconCode />,
        title: 'Код (строчный)',
        action: () => applyTransform(wrapSelection(textareaRef.current, '`')),
      },
    ],
    // Block-level
    [
      {
        icon: <IconCodeBlock />,
        title: 'Блок кода',
        action: () => applyTransform(insertCodeBlock(textareaRef.current)),
      },
      { icon: <IconQuote />, title: 'Цитата', action: () => applyTransform(prependLine(textareaRef.current, '> ')) },
      {
        icon: <IconList />,
        title: 'Маркированный список',
        action: () => applyTransform(prependLine(textareaRef.current, '- ')),
      },
      {
        icon: <IconOrderedList />,
        title: 'Нумерованный список',
        action: () => applyTransform(prependLine(textareaRef.current, '1. ')),
      },
      {
        icon: <IconChecklist />,
        title: 'Чек-лист',
        action: () => applyTransform(prependLine(textareaRef.current, '- [ ] ')),
      },
      { icon: <IconHr />, title: 'Разделитель', action: () => applyTransform(insertBlock(textareaRef.current, '---')) },
    ],
    // Insert
    [
      {
        icon: <IconLink />,
        title: 'Ссылка',
        action: () => applyTransform(wrapSelection(textareaRef.current, '[', '](url)')),
      },
      {
        icon: <IconImage />,
        title: 'Изображение',
        action: () => applyTransform(wrapSelection(textareaRef.current, '![', '](url)')),
      },
      { icon: <IconTable />, title: 'Таблица', action: () => applyTransform(insertTable(textareaRef.current)) },
    ],
  ];

  // Tab key → indent with 2 spaces; also route to mention keyboard handler
  const handleKeyDown = (e) => {
    // Let @mention consume arrow keys / Enter / Escape when active
    mention.handleKeyDown(e);
    if (e.defaultPrevented) return;

    if (e.key === 'Tab') {
      e.preventDefault();
      const ta = e.target;
      const { selectionStart: s, selectionEnd: end, value: v } = ta;
      const newVal = v.slice(0, s) + '  ' + v.slice(end);
      setVal(newVal);
      setDirty(true);
      requestAnimationFrame(() => {
        ta.setSelectionRange(s + 2, s + 2);
      });
    }
  };

  // ── Preview-only mode (used in SummarySection About + fullscreen About) ────
  // No toolbar here. The "copy all" affordance for About lives in
  // SummarySection's header (left of the pencil), so this stays a clean render.
  if (previewOnly) {
    return (
      <div className="md-preview md-preview--embedded">
        {val ? (
          <ReactMarkdown
            remarkPlugins={REMARK_PLUGINS}
            rehypePlugins={REHYPE_PLUGINS}
            components={getMarkdownComponents(tree, onNavigate)}
          >
            {val}
          </ReactMarkdown>
        ) : (
          <p className="md-preview__empty">Нечего показывать</p>
        )}
      </div>
    );
  }

  // ── Full editor mode ──────────────────────────────────────────────────────
  return (
    <div className="md-editor">
      {/* Toolbar */}
      <div className="md-toolbar">
        <div className="md-toolbar__group">
          {toolbarGroups.map((group, gi) => (
            <React.Fragment key={gi}>
              {gi > 0 && <span className="md-toolbar__sep" />}
              {group.map(({ icon, title, action }) => (
                <ToolbarBtn key={title} icon={icon} title={title} onClick={action} disabled={preview} />
              ))}
            </React.Fragment>
          ))}
        </div>
        <div className="md-toolbar__group md-toolbar__group--right">
          <ToolbarBtn
            icon={copied ? <IconCheck /> : <IconCopy />}
            title={copied ? 'Скопировано' : 'Копировать всё'}
            onClick={handleCopyAll}
            active={copied}
          />
          <ToolbarBtn
            icon={preview ? <IconEyeOff /> : <IconEye />}
            title={preview ? 'Редактор' : 'Предпросмотр'}
            onClick={() => setPreview((p) => !p)}
          />
          {onExpand && <ToolbarBtn icon={<IconExpand />} title="Развернуть" onClick={onExpand} />}
          {onHistory && <ToolbarBtn icon={<IconHistory />} title="История изменений" onClick={onHistory} />}
        </div>
      </div>

      {/* Pane */}
      <div className="md-pane" style={{ position: 'relative' }}>
        {!preview && (
          <>
            <textarea
              ref={textareaRef}
              className="md-textarea"
              placeholder={placeholder}
              value={val}
              onChange={(e) => {
                setVal(e.target.value);
                setDirty(true);
              }}
              onKeyDown={handleKeyDown}
              spellCheck={false}
            />

            {/* @mention dropdown — rendered in a portal-like fixed div via CSS position:fixed */}
            {mention.active && (
              <AtMentionDropdown
                results={mention.results}
                loading={mention.loading}
                query={mention.query}
                anchorRect={mention.anchorRect}
                selectedIdx={mention.selectedIdx}
                onSelect={mention.select}
                onDismiss={mention.dismiss}
              />
            )}
          </>
        )}

        {preview && (
          <div className="md-preview">
            {val ? (
              <ReactMarkdown
                remarkPlugins={REMARK_PLUGINS}
                rehypePlugins={REHYPE_PLUGINS}
                components={getMarkdownComponents(tree, onNavigate)}
              >
                {val}
              </ReactMarkdown>
            ) : (
              <p className="md-preview__empty">Нечего показывать</p>
            )}
          </div>
        )}
      </div>

      {/* Save bar */}
      {dirty && (
        <div className="save-bar">
          <button
            className="save-bar__save"
            disabled={saving}
            onClick={async () => {
              setSaving(true);
              try {
                await onSave(val);
                setDirty(false);
              } catch (err) {
                console.error('Save error in MarkdownEditor:', err);
              } finally {
                setSaving(false);
              }
            }}
          >
            {saving ? 'Сохранение...' : 'Сохранить'}
          </button>
          <button
            className="save-bar__cancel"
            disabled={saving}
            onClick={() => {
              setVal(value);
              setDirty(false);
            }}
          >
            Отмена
          </button>
        </div>
      )}
    </div>
  );
};

export default MarkdownEditor;
