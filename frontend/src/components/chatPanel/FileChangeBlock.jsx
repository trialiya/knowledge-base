import React, { useState, useMemo, useEffect } from 'react';
import ReactDOM from 'react-dom';
import { useTranslation } from 'react-i18next';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { getFileChangeRef } from './toolMeta';
import { TOOL_STATUS } from '../../constants/toolStatus';
import { fetchContent } from './fileChips';
import { IconChevronDown } from '../../icons';
import './styles/doc-changes.css';
import './styles/file-changes.css';

/**
 * Блок под ответом ИИ: файловые мутации (createFile/editFile) из toolCalls.
 * Строка на файл: путь, операция, +N/−M; клик открывает модалку со всеми
 * diff'ами правок этого файла из данного ответа (diff приходит в resultMeta —
 * работает и в live-стриме, и после перезагрузки чата, как у DocChangeBlock).
 */
const FileChangeBlock = ({ toolCalls }) => {
  const { t } = useTranslation('chat');
  const [target, setTarget] = useState(null); // { path, operation, additions, deletions, diffs } | null
  const [open, setOpen] = useState(false);

  // Одна строка на файл: суммарные +/− по всем успешным правкам, diff'ы копятся
  // в порядке выполнения. Упавшие вызовы (ERROR) пропускаются — они файл не меняли.
  const changes = useMemo(() => {
    const byPath = new Map();
    for (const tc of toolCalls || []) {
      const ref = getFileChangeRef(tc);
      if (!ref || ref.status === TOOL_STATUS.ERROR) continue;
      const cur = byPath.get(ref.path);
      if (!cur) {
        byPath.set(ref.path, { ...ref, diffs: ref.diff ? [ref.diff] : [] });
      } else {
        cur.additions += ref.additions;
        cur.deletions += ref.deletions;
        if (ref.operation === 'create') cur.operation = 'create';
        if (ref.diff) cur.diffs.push(ref.diff);
      }
    }
    return [...byPath.values()];
  }, [toolCalls]);

  if (changes.length === 0) return null;

  return (
    <div className="doc-change-block">
      <button
        type="button"
        className="change-block-summary"
        onClick={() => setOpen((v) => !v)}
        aria-expanded={open}
      >
        <span className="change-block-summary-icon" aria-hidden="true">
          📝
        </span>
        <span className="change-block-summary-text">
          {t('fileChange.summary', { count: changes.length, defaultValue: `Files changed (${changes.length})` })}
        </span>
        <span className={`change-block-chevron ${open ? 'change-block-chevron--open' : ''}`}>
          <IconChevronDown />
        </span>
      </button>

      {open &&
        changes.map((c) => (
          <button
            key={c.path}
            type="button"
            className="doc-change-item"
            onClick={() => setTarget(c)}
            title={t('fileChange.viewChanges')}
          >
            <span className="doc-change-icon" aria-hidden="true">
              {c.operation === 'create' ? '🆕' : '✏️'}
            </span>
            <span className="doc-change-text">
              <span className="doc-change-title">{c.path}</span>
              <span className="doc-change-sub">
                {c.operation === 'create' ? t('fileChange.created') : t('fileChange.edited')}
                <span className="file-change-stats">
                  {' · '}
                  <span className="file-change-add">+{c.additions}</span>/
                  <span className="file-change-del">−{c.deletions}</span>
                </span>
              </span>
            </span>
            <span className="doc-change-cta">{t('fileChange.viewChanges')} ›</span>
          </button>
        ))}

      {target && <FileDiffModal change={target} onClose={() => setTarget(null)} />}
    </div>
  );
};

const isMarkdownPath = (path) => /\.mdx?$/i.test(path || '');

/** Раскраска строк unified diff: добавленные/удалённые/заголовки хунков. */
const diffLineClass = (line) => {
  if (line.startsWith('+')) return 'file-diff-line file-diff-line--add';
  if (line.startsWith('-')) return 'file-diff-line file-diff-line--del';
  if (line.startsWith('@@')) return 'file-diff-line file-diff-line--hunk';
  return 'file-diff-line';
};

const FileDiffModal = ({ change, onClose }) => {
  const { t } = useTranslation('chat');
  const isMd = isMarkdownPath(change.path);
  const [mdView, setMdView] = useState(false);
  const showsContent = change.diffs.length === 0;
  const [content, setContent] = useState(null);
  const [loading, setLoading] = useState(showsContent);
  const [error, setError] = useState(false);

  useEffect(() => {
    if (!showsContent) return undefined;
    let cancelled = false;
    setLoading(true);
    setError(false);
    fetchContent(change.path)
      .then((data) => {
        if (cancelled) return;
        setContent(data);
        setLoading(false);
      })
      .catch(() => {
        if (!cancelled) {
          setError(true);
          setLoading(false);
        }
      });
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [change.path, showsContent]);

  return ReactDOM.createPortal(
    <div className="fcd-overlay" onClick={onClose}>
      <div className="fcd-modal" role="dialog" aria-modal="true" onClick={(e) => e.stopPropagation()}>
        <div className="fcd-header">
          <span className="fcd-title" title={change.path}>
            {change.path}
            <span className="file-change-stats">
              {' '}
              <span className="file-change-add">+{change.additions}</span>/
              <span className="file-change-del">−{change.deletions}</span>
            </span>
          </span>
          {isMd && (
            <button
              type="button"
              className={`fcd-md-toggle${mdView ? ' fcd-md-toggle--active' : ''}`}
              onClick={() => setMdView((v) => !v)}
              title={t('fileChange.toggleMarkdown', { defaultValue: 'Markdown preview' })}
            >
              {mdView ? '{ }' : '👁'}
            </button>
          )}
          <a
            className="fcd-open-link"
            href={`/files?path=${encodeURIComponent(change.path)}`}
            target="_blank"
            rel="noreferrer"
          >
            {t('fileChange.openFile')}
          </a>
          <button className="fcd-close" onClick={onClose} title={t('common:close')} type="button">
            ✕
          </button>
        </div>
        <div className="fcd-body">
          {change.diffs.length === 0 ? (
            <>
              {loading && <div className="fcd-empty">{t('loading')}</div>}
              {!loading && error && <div className="fcd-empty">{t('fileChange.loadError')}</div>}
              {!loading && !error && content?.binary && (
                <div className="fcd-empty">{t('fileChips.binaryFile')}</div>
              )}
              {!loading && !error && content && !content.binary && (
                <>
                  {mdView ? (
                    <div className="fcd-md-preview">
                      <ReactMarkdown remarkPlugins={[remarkGfm]}>{content.content || ''}</ReactMarkdown>
                    </div>
                  ) : (
                    <pre className="fcd-diff fcd-content">{content.content || ''}</pre>
                  )}
                </>
              )}
            </>
          ) : (
            change.diffs.map((diff, i) => (
              // Индекс как key безопасен: список diff'ов иммутабелен в рамках открытой модалки.
              // eslint-disable-next-line react/no-array-index-key
              <pre key={i} className="fcd-diff">
                {diff.split('\n').map((line, j) => (
                  // eslint-disable-next-line react/no-array-index-key
                  <span key={j} className={diffLineClass(line)}>
                    {line}
                    {'\n'}
                  </span>
                ))}
              </pre>
            ))
          )}
        </div>
      </div>
    </div>,
    document.body,
  );
};

export default FileChangeBlock;
