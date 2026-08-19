import { useState, useMemo, useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { getFileChangeRefs } from './toolMeta';
import { TOOL_STATUS } from '../../../constants/toolStatus';
import { fetchContent } from '../composer/fileChips';
import { IconChevronDown } from '../../../icons';
import ModalShell from '../../common/modal/ModalShell';
import { DiffLines, DiffStats } from './diffRender';
import { filesUrl } from '../../../urlScheme';
import '../styles/doc-changes.css';
import '../styles/file-changes.css';

/**
 * Блок под ответом ИИ: файловые мутации (createFile/editFile/runScript) из toolCalls.
 * Строка на файл: путь, операция, +N/−M; клик открывает модалку со всеми
 * diff'ами правок этого файла из данного ответа (diff приходит в resultMeta —
 * работает и в live-стриме, и после перезагрузки чата, как у DocChangeBlock).
 */
const FileChangeBlock = ({ toolCalls, project }) => {
  const { t } = useTranslation('chat');
  const [target, setTarget] = useState(null); // { path, operation, additions, deletions, diffs } | null
  const [open, setOpen] = useState(false);

  // Одна строка на файл: суммарные +/− по всем успешным правкам, diff'ы копятся
  // в порядке выполнения. Упавшие вызовы (ERROR) пропускаются — они файл не меняли.
  const changes = useMemo(() => {
    const byPath = new Map();
    for (const tc of toolCalls || []) {
      // Один вызов может принести несколько правок: runScript пишет пачкой.
      for (const ref of getFileChangeRefs(tc)) {
        if (ref.status === TOOL_STATUS.ERROR) continue;
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
    }
    return [...byPath.values()];
  }, [toolCalls]);

  if (changes.length === 0) return null;

  return (
    <div className="doc-change-block">
      <button type="button" className="change-block-summary" onClick={() => setOpen((v) => !v)} aria-expanded={open}>
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
                {' · '}
                <DiffStats additions={c.additions} deletions={c.deletions} />
              </span>
            </span>
            <span className="doc-change-cta">{t('fileChange.viewChanges')} ›</span>
          </button>
        ))}

      {target && <FileDiffModal change={target} project={project} onClose={() => setTarget(null)} />}
    </div>
  );
};

const isMarkdownPath = (path) => /\.mdx?$/i.test(path || '');

const FileDiffModal = ({ change, project, onClose }) => {
  const { t } = useTranslation('chat');
  const isMd = isMarkdownPath(change.path);
  const [mdView, setMdView] = useState(false);
  const showsContent = change.diffs.length === 0;
  // Ответ сервера; null — запрос ещё идёт. `loading`/`error` выводятся из него
  // при рендере, а сброс на смену файла делается тут же — эффект показал бы
  // кадр с содержимым предыдущего.
  const [answer, setAnswer] = useState(null); // { content, error } | null

  const [req, setReq] = useState({ path: change.path, project, showsContent });
  if (req.path !== change.path || req.project !== project || req.showsContent !== showsContent) {
    setReq({ path: change.path, project, showsContent });
    setAnswer(null);
  }

  useEffect(() => {
    if (!showsContent) return undefined;
    let cancelled = false;
    fetchContent(change.path, { project })
      .then((data) => {
        if (!cancelled) setAnswer({ content: data, error: false });
      })
      .catch(() => {
        if (!cancelled) setAnswer({ content: null, error: true });
      });
    return () => {
      cancelled = true;
    };
  }, [change.path, project, showsContent]);

  const loading = showsContent && answer === null;
  const content = answer?.content ?? null;
  const error = answer?.error ?? false;

  return (
    <ModalShell onClose={onClose} className="fcd-modal">
      <div className="fcd-header">
        <span className="fcd-title" title={change.path}>
          {change.path} <DiffStats additions={change.additions} deletions={change.deletions} />
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
        <a className="fcd-open-link" href={filesUrl(change.path, project)} target="_blank" rel="noreferrer">
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
            {!loading && !error && content?.binary && <div className="fcd-empty">{t('fileChips.binaryFile')}</div>}
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

            <pre key={i} className="fcd-diff">
              <DiffLines patch={diff} />
            </pre>
          ))
        )}
      </div>
    </ModalShell>
  );
};

export default FileChangeBlock;
