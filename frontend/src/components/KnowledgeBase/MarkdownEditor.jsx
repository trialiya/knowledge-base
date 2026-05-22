import React, { useState, useEffect, useRef, useCallback } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { IconBold, IconItalic, IconCode, IconLink, IconH1, IconList, IconQuote, IconEye, IconEyeOff } from './icons';

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
  // Return new value and new cursor range
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
 */
const MarkdownEditor = ({ value, placeholder = '# Markdown...', onSave, previewOnly = false }) => {
  const [val, setVal] = useState(value);
  const [dirty, setDirty] = useState(false);
  const [preview, setPreview] = useState(false);
  const [saving, setSaving] = useState(false);
  const textareaRef = useRef(null);

  useEffect(() => {
    setVal(value);
    setDirty(false);
  }, [value]);

  const applyTransform = useCallback(({ newVal, from, to }) => {
    setVal(newVal);
    setDirty(true);
    // Restore focus + selection after React re-render
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

  // Tab key → indent with 2 spaces
  const handleKeyDown = (e) => {
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

  // ─── Preview-only mode (used in SummarySection) ───────────────────────────
  if (previewOnly) {
    return (
      <div className="md-preview md-preview--embedded">
        {val ? (
          <ReactMarkdown remarkPlugins={[remarkGfm]}>{val}</ReactMarkdown>
        ) : (
          <p className="md-preview__empty">Нечего показывать</p>
        )}
      </div>
    );
  }

  // ─── Full editor mode ─────────────────────────────────────────────────────
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
      <div className="md-pane">
        {!preview && (
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
        )}
        {preview && (
          <div className="md-preview">
            {val ? (
              <ReactMarkdown remarkPlugins={[remarkGfm]}>{val}</ReactMarkdown>
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
                // При ошибке изменения остаются в редакторе
                console.error('Save error in MarkdownEditor:', err);
                // dirty остается true, панель сохранения не исчезает
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
