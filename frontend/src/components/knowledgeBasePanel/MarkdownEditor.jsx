import React, { useState, useEffect, useRef, useCallback, useMemo } from 'react';
import { useTranslation } from 'react-i18next';
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
} from '../../icons';
import DocLinkTooltip from '../common/DocLinkTooltip';
import AtMentionDropdown from './AtMentionDropdown';
import useAtMention from './useAtMention';
import CodeBlock from '../common/CodeBlock';
import { setEditorDirty } from './editorDirtyStore';
import { COPY_DONE_MS } from '../../constants/ui';

// remark / rehype plugin arrays — stable references so ReactMarkdown doesn't
// rebuild its processor on every render. rehypeSlug adds GitHub-style `id`s to
// headings (same slugger that produced the `#обзор` anchors in tables of
// contents), so in-document anchor links resolve. DocLinkTooltip handles the
// click → scroll-into-view (see its hash-link branch).
const REMARK_PLUGINS = [remarkGfm];
const REHYPE_PLUGINS = [rehypeSlug];

// ─── Markdown components factory ──────────────────────────────────────────────
// Returns a ReactMarkdown `components` map that intercepts /?doc=N links.
// Memoized per (tree, onNavigate) pair by the component below so ReactMarkdown
// keeps a stable reference between renders.

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
// Локализуемые «текстовые заглушки» (вставляемый текст, который пользователь
// перепечатывает) передаются параметрами из компонента, где доступен t().

function wrapSelection(textarea, before, after = before, fallback = 'текст') {
  const { selectionStart: s, selectionEnd: e, value } = textarea;
  const selected = value.slice(s, e) || fallback;
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

function insertCodeBlock(textarea, codeWord = 'код') {
  const { selectionStart: s, selectionEnd: e, value } = textarea;
  const selected = value.slice(s, e);
  const body = selected || codeWord;
  return insertBlock(textarea, '```\n' + body + '\n```', selected ? null : codeWord);
}

function insertTable(textarea, labels = { col1: 'Колонка 1', col2: 'Колонка 2', cell: 'Ячейка' }) {
  const { col1, col2, cell } = labels;
  const table = [`| ${col1} | ${col2} |`, '| --- | --- |', `| ${cell} | ${cell} |`].join('\n');
  return insertBlock(textarea, table, col1);
}

// ─── Component ────────────────────────────────────────────────────────────────

/**
 * props:
 *   value       — markdown string (контролируемый текущий текст / черновик)
 *   onChange    — (val: string) => void; вызывается на каждое изменение текста.
 *                 Делает редактор контролируемым; нужен для всех editable-инстансов.
 *   savedValue  — последнее сохранённое описание; «грязно» = value !== savedValue
 *   placeholder — placeholder text for textarea (по умолчанию t('editor.placeholder'))
 *   onSave      — async (val: string) => void
 *   previewOnly — if true, renders only the preview pane without toolbar or editor controls
 *   tree        — KB tree array (for DocLinkTooltip instant lookup)
 *   onNavigate  — (node) => void (for DocLinkTooltip "Открыть" button)
 *   onExpand    — optional () => void; if set, shows an "expand" button in the toolbar right group
 *   onHistory   — optional () => void; if set, shows a "history" button
 */
const MarkdownEditor = ({
  value = '',
  onChange,
  savedValue = '',
  placeholder,
  onSave,
  previewOnly = false,
  tree = [],
  onNavigate,
  onExpand,
  onHistory,
}) => {
  const { t } = useTranslation('knowledgeBase');
  const [preview, setPreview] = useState(false);
  const [saving, setSaving] = useState(false);
  const [copied, setCopied] = useState(false);
  const textareaRef = useRef(null);
  const copyTimerRef = useRef(null);
  // Stable per-instance id for the shared dirty registry.
  const dirtyIdRef = useRef(`md-${Math.random().toString(36).slice(2)}`);

  // Stable ReactMarkdown components map (was rebuilt every render before).
  const mdComponents = useMemo(() => getMarkdownComponents(tree, onNavigate), [tree, onNavigate]);

  // Контролируемый редактор: текущий текст живёт в `value` (черновик «поднят» к
  // родителю — DocumentDetail/FolderDetail), а правки уходят через `onChange`.
  // Благодаря этому встроенный и полноэкранный («развернуть») редакторы делят
  // один источник правды, и развёрнутое окно открывается с текущими правками.
  // «Грязно» = черновик разошёлся с сохранённым описанием.
  const dirty = value !== savedValue;
  const update = useCallback((next) => onChange && onChange(next), [onChange]);

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

  const mention = useAtMention(textareaRef, value, (newVal, newCursor) => {
    update(newVal);
    requestAnimationFrame(() => {
      const ta = textareaRef.current;
      if (ta) ta.setSelectionRange(newCursor, newCursor);
    });
  });

  // ── Toolbar transforms ──────────────────────────────────────────────────────

  const applyTransform = useCallback(
    ({ newVal, from, to }) => {
      update(newVal);
      requestAnimationFrame(() => {
        const ta = textareaRef.current;
        if (!ta) return;
        ta.focus();
        ta.setSelectionRange(from, to);
      });
    },
    [update],
  );

  // Copy the full markdown source to the clipboard.
  const handleCopyAll = useCallback(async () => {
    try {
      await navigator.clipboard.writeText(value);
      setCopied(true);
      clearTimeout(copyTimerRef.current);
      copyTimerRef.current = setTimeout(() => setCopied(false), COPY_DONE_MS);
    } catch {
      /* clipboard API недоступен в insecure context */
    }
  }, [value]);

  const handleSave = useCallback(async () => {
    setSaving(true);
    try {
      await onSave(value);
      // dirty снимется само: родитель обновит savedValue до сохранённого текста.
    } catch (err) {
      console.error('Save error in MarkdownEditor:', err);
    } finally {
      setSaving(false);
    }
  }, [onSave, value]);

  const handleCancelEdit = useCallback(() => {
    update(savedValue);
  }, [update, savedValue]);

  // Toolbar is rebuilt only when the transform fn or the localized labels change
  // (i.e. on language switch), not on every keystroke.
  const toolbarGroups = useMemo(() => {
    const txt = t('editor.insertText');
    const codeWord = t('editor.insertCode');
    const tableLabels = { col1: t('editor.tableCol1'), col2: t('editor.tableCol2'), cell: t('editor.tableCell') };

    return [
      // Inline text formatting
      [
        {
          icon: <IconH1 />,
          title: t('editor.heading'),
          action: () => applyTransform(prependLine(textareaRef.current, '## ')),
        },
        {
          icon: <IconBold />,
          title: t('editor.bold'),
          action: () => applyTransform(wrapSelection(textareaRef.current, '**', '**', txt)),
        },
        {
          icon: <IconItalic />,
          title: t('editor.italic'),
          action: () => applyTransform(wrapSelection(textareaRef.current, '_', '_', txt)),
        },
        {
          icon: <IconStrike />,
          title: t('editor.strike'),
          action: () => applyTransform(wrapSelection(textareaRef.current, '~~', '~~', txt)),
        },
        {
          icon: <IconCode />,
          title: t('editor.inlineCode'),
          action: () => applyTransform(wrapSelection(textareaRef.current, '`', '`', txt)),
        },
      ],
      // Block-level
      [
        {
          icon: <IconCodeBlock />,
          title: t('editor.codeBlock'),
          action: () => applyTransform(insertCodeBlock(textareaRef.current, codeWord)),
        },
        {
          icon: <IconQuote />,
          title: t('editor.quote'),
          action: () => applyTransform(prependLine(textareaRef.current, '> ')),
        },
        {
          icon: <IconList />,
          title: t('editor.bulletList'),
          action: () => applyTransform(prependLine(textareaRef.current, '- ')),
        },
        {
          icon: <IconOrderedList />,
          title: t('editor.orderedList'),
          action: () => applyTransform(prependLine(textareaRef.current, '1. ')),
        },
        {
          icon: <IconChecklist />,
          title: t('editor.checklist'),
          action: () => applyTransform(prependLine(textareaRef.current, '- [ ] ')),
        },
        {
          icon: <IconHr />,
          title: t('editor.divider'),
          action: () => applyTransform(insertBlock(textareaRef.current, '---')),
        },
      ],
      // Insert
      [
        {
          icon: <IconLink />,
          title: t('editor.link'),
          action: () => applyTransform(wrapSelection(textareaRef.current, '[', '](url)', txt)),
        },
        {
          icon: <IconImage />,
          title: t('editor.image'),
          action: () => applyTransform(wrapSelection(textareaRef.current, '![', '](url)', txt)),
        },
        {
          icon: <IconTable />,
          title: t('editor.table'),
          action: () => applyTransform(insertTable(textareaRef.current, tableLabels)),
        },
      ],
    ];
  }, [t, applyTransform]);

  // Tab → отступ 2 пробела; также маршрутизируем клавиши в @mention-обработчик
  const handleKeyDown = (e) => {
    // Let @mention consume arrow keys / Enter / Escape when active
    mention.handleKeyDown(e);
    if (e.defaultPrevented) return;

    if (e.key === 'Tab') {
      e.preventDefault();
      const ta = e.target;
      const { selectionStart: s, selectionEnd: end, value: v } = ta;
      const INDENT = '  ';

      if (s === end) {
        // Нет выделения — просто вставляем отступ в позицию каретки.
        const newVal = v.slice(0, s) + INDENT + v.slice(end);
        update(newVal);
        requestAnimationFrame(() => ta.setSelectionRange(s + INDENT.length, s + INDENT.length));
      } else {
        // Есть выделение — добавляем отступ КАЖДОЙ затронутой строке, сохраняя
        // сам текст и охват выделения (раньше выделение затиралось двумя пробелами).
        const lineStart = v.lastIndexOf('\n', s - 1) + 1;
        const block = v.slice(lineStart, end);
        const indented = block.replace(/^(?=.)/gm, INDENT); // пустые строки не трогаем
        const added = indented.length - block.length;
        const newVal = v.slice(0, lineStart) + indented + v.slice(end);
        update(newVal);
        // start сдвигаем на отступ первой строки, end — на суммарно добавленное.
        const newStart = s + (indented.startsWith(INDENT) ? INDENT.length : 0);
        requestAnimationFrame(() => ta.setSelectionRange(newStart, end + added));
      }
    }
  };

  // ── Preview-only mode (used in SummarySection About + fullscreen About) ────
  if (previewOnly) {
    return (
      <div className="md-preview md-preview--embedded">
        {value ? (
          <ReactMarkdown remarkPlugins={REMARK_PLUGINS} rehypePlugins={REHYPE_PLUGINS} components={mdComponents}>
            {value}
          </ReactMarkdown>
        ) : (
          <p className="md-preview__empty">{t('editor.emptyPreview')}</p>
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
            title={copied ? t('editor.copied') : t('editor.copyAll')}
            onClick={handleCopyAll}
            active={copied}
          />
          <ToolbarBtn
            icon={preview ? <IconEyeOff /> : <IconEye />}
            title={preview ? t('editor.editorView') : t('editor.preview')}
            onClick={() => setPreview((p) => !p)}
          />
          {onExpand && <ToolbarBtn icon={<IconExpand />} title={t('editor.expand')} onClick={onExpand} />}
          {onHistory && <ToolbarBtn icon={<IconHistory />} title={t('editor.history')} onClick={onHistory} />}
        </div>
      </div>

      {/* Pane */}
      <div className="md-pane" style={{ position: 'relative' }}>
        {!preview && (
          <>
            <textarea
              ref={textareaRef}
              className="md-textarea"
              placeholder={placeholder ?? t('editor.placeholder')}
              value={value}
              onChange={(e) => update(e.target.value)}
              onKeyDown={handleKeyDown}
              spellCheck={false}
            />

            {/* @mention dropdown — rendered via CSS position:fixed */}
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
            {value ? (
              <ReactMarkdown remarkPlugins={REMARK_PLUGINS} rehypePlugins={REHYPE_PLUGINS} components={mdComponents}>
                {value}
              </ReactMarkdown>
            ) : (
              <p className="md-preview__empty">{t('editor.emptyPreview')}</p>
            )}
          </div>
        )}
      </div>

      {/* Save bar */}
      {dirty && (
        <div className="save-bar">
          <button className="save-bar__save" disabled={saving} onClick={handleSave}>
            {saving ? t('editor.saving') : t('editor.save')}
          </button>
          <button className="save-bar__cancel" disabled={saving} onClick={handleCancelEdit}>
            {t('editor.cancel')}
          </button>
        </div>
      )}
    </div>
  );
};

export default MarkdownEditor;
