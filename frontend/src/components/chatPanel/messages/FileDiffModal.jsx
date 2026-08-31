import { useState, useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { fetchContent } from '../composer/fileChips';
import ModalShell from '@/components/common/modal/ModalShell';
import { DiffLines, DiffStats } from './diffRender';
import { filesUrl } from '@/navigation/urlScheme';
import '../styles/file-changes.css';

const isMarkdownPath = (path) => /\.mdx?$/i.test(path || '');

/**
 * Один изменённый ответом ИИ файл крупным планом: diff'ы всех правок этого файла из данного
 * ответа, а у созданного файла (diff'а у него нет) — его текущее содержимое.
 */
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

export default FileDiffModal;
