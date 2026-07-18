import React, { useState } from 'react';
import { useTranslation } from 'react-i18next';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { baseName } from './fileChips';
import ModalShell from '../common/ModalShell';
import { IconX } from '../../icons';

// ── Превью содержимого файла — полноэкранная модалка ─────────────────────────

function FileChipPreview({ preview, onClose, onToggleRef }) {
  const { path, from, to, refOnly, loading, data, error } = preview;
  const { t } = useTranslation('chat');
  const name = baseName(path);
  const range = from != null ? ` (${from}–${to})` : '';
  const isMd = /\.mdx?$/i.test(path || '');
  const [mdView, setMdView] = useState(false);

  return (
    <ModalShell onClose={onClose} variant="fullscreen" className="file-preview-modal">
      <div className="fs-editor__head">
        <div className="file-preview-modal__title">
          <span className="file-preview-modal__name">
            {name}
            {range}
          </span>
          <span className="file-preview-modal__path" title={path}>
            {path}
          </span>
        </div>
        {isMd && !refOnly && (
          <button
            type="button"
            className={'file-preview-modal__toggle' + (mdView ? ' file-preview-modal__toggle--active' : '')}
            onClick={() => setMdView((v) => !v)}
            title={t('fileChange.toggleMarkdown', { defaultValue: 'Markdown preview' })}
          >
            {mdView ? '{ }' : '👁'}
          </button>
        )}
        <button
          type="button"
          className={'file-preview-modal__toggle' + (refOnly ? ' file-preview-modal__toggle--active' : '')}
          onClick={onToggleRef}
          title={refOnly ? t('fileInput.useFullContent') : t('fileInput.usePathOnly')}
        >
          {refOnly ? '📄' : '📎'}
        </button>
        <button className="fs-editor__close" title={t('fileInput.closePreview')} onClick={onClose}>
          <IconX />
        </button>
      </div>
      <div className="fs-editor__body file-preview-modal__body">
        {!refOnly && (
          <>
            {loading && <div className="file-preview-modal__msg">{t('fileInput.searching')}</div>}
            {error && <div className="file-preview-modal__msg">{t('fileInput.previewError')}</div>}
            {!loading && !error && data?.binary && (
              <div className="file-preview-modal__msg">{t('fileChips.binaryFile')}</div>
            )}
            {!loading && !error && !data?.binary && mdView && (
              <div className="file-preview-modal__md">
                <ReactMarkdown remarkPlugins={[remarkGfm]}>{data?.content ?? ''}</ReactMarkdown>
              </div>
            )}
            {!loading && !error && !data?.binary && !mdView && (
              <pre className="file-preview-modal__code">{data?.content ?? ''}</pre>
            )}
          </>
        )}
        {refOnly && <div className="file-preview-modal__ref-note">{path}</div>}
      </div>
    </ModalShell>
  );
}

export default FileChipPreview;
