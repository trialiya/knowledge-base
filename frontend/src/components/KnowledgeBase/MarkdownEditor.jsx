import React, { useState, useEffect, useRef, useCallback } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { IconBold, IconItalic, IconCode, IconLink, IconH1, IconList, IconQuote, IconEye, IconEyeOff } from './icons';
import DocLinkTooltip from './DocLinkTooltip';
import AtMentionDropdown from './AtMentionDropdown';
import useAtMention from './useAtMention';

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
  };
}

// ─── Toolbar button ───────────────────────────────────────────────────────────

const ToolbarBtn = ({ icon, title, onClick, disabled }) => (
  <button
    className="md-toolbar__btn"
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

// ─── Component ────────────────────────────────────────────────────────────────

/**
 * props:
 *   value       — markdown string
 *   placeholder — placeholder text for textarea
 *   onSave      — async (val: string) => void
 *   previewOnly — if true, renders only the preview pane without toolbar or editor controls
 *   tree        — KB tree array (for DocLinkTooltip instant lookup)
 *   onNavigate  — (node) => void (for DocLinkTooltip "Открыть" button)
 */
const MarkdownEditor = ({
  value,
  placeholder = '# Markdown...',
  onSave,
  previewOnly = false,
  tree = [],
  onNavigate,
}) => {
  const [val, setVal] = useState(value);
  const [dirty, setDirty] = useState(false);
  const [preview, setPreview] = useState(false);
  const [saving, setSaving] = useState(false);
  const textareaRef = useRef(null);

  useEffect(() => {
    setVal(value);
    setDirty(false);
  }, [value]);

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

  const toolbar = [
    { icon: <IconH1 />, title: 'Heading', action: () => applyTransform(prependLine(textareaRef.current, '## ')) },
    { icon: <IconBold />, title: 'Bold', action: () => applyTransform(wrapSelection(textareaRef.current, '**')) },
    { icon: <IconItalic />, title: 'Italic', action: () => applyTransform(wrapSelection(textareaRef.current, '_')) },
    { icon: <IconCode />, title: 'Code', action: () => applyTransform(wrapSelection(textareaRef.current, '`')) },
    { icon: <IconQuote />, title: 'Quote', action: () => applyTransform(prependLine(textareaRef.current, '> ')) },
    { icon: <IconList />, title: 'List item', action: () => applyTransform(prependLine(textareaRef.current, '- ')) },
    {
      icon: <IconLink />,
      title: 'Link',
      action: () => applyTransform(wrapSelection(textareaRef.current, '[', '](url)')),
    },
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

  // ── Preview-only mode (used in SummarySection) ────────────────────────────
  if (previewOnly) {
    return (
      <div className="md-preview md-preview--embedded">
        {val ? (
          <ReactMarkdown remarkPlugins={[remarkGfm]} components={getMarkdownComponents(tree, onNavigate)}>
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
          {toolbar.map(({ icon, title, action }) => (
            <ToolbarBtn key={title} icon={icon} title={title} onClick={action} disabled={preview} />
          ))}
        </div>
        <div className="md-toolbar__group md-toolbar__group--right">
          <ToolbarBtn
            icon={preview ? <IconEyeOff /> : <IconEye />}
            title={preview ? 'Редактор' : 'Предпросмотр'}
            onClick={() => setPreview((p) => !p)}
          />
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
              <ReactMarkdown remarkPlugins={[remarkGfm]} components={getMarkdownComponents(tree, onNavigate)}>
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
