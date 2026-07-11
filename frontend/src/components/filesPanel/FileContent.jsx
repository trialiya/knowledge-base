import React from 'react';
import { useTranslation } from 'react-i18next';
import { IconFolder, IconDoc } from '../../icons';
import { formatFileSize } from '../../utils/formatting';
import Breadcrumb from './Breadcrumb';

const CodeView = ({ text, fromLine = 1 }) => {
  const lines = text.split('\n');
  return (
    <div className="file-code">
      <table className="file-code__table">
        <tbody>
          {lines.map((line, i) => (
            // eslint-disable-next-line react/no-array-index-key
            <tr key={i}>
              <td className="file-code__gutter">{fromLine + i}</td>
              <td className="file-code__line">
                <code>{line.length ? line : ' '}</code>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
};

const DirectoryListing = ({ nodes, onNavigate }) => {
  const { t } = useTranslation('files');
  if (nodes.length === 0) {
    return <div className="file-content__empty">{t('tree.empty')}</div>;
  }
  return (
    <div className="file-listing">
      {nodes.map((node) => (
        <button key={node.path} className="file-listing__row" onClick={() => onNavigate(node.path)}>
          <span className={`file-listing__icon ${node.type === 'directory' ? 'file-listing__icon--folder' : ''}`}>
            {node.type === 'directory' ? <IconFolder /> : <IconDoc />}
          </span>
          <span className="file-listing__name">{node.name}</span>
          {node.type === 'file' && node.size != null && (
            <span className="file-listing__size">{formatFileSize(node.size)}</span>
          )}
        </button>
      ))}
    </div>
  );
};

const FileView = ({ file }) => {
  const { t } = useTranslation('files');
  return (
    <div className="file-view">
      <div className="file-view__meta">
        {file.language && <span className="file-view__badge">{file.language}</span>}
        <span>{t('file.lines', { count: file.lineCount })}</span>
        <span>{formatFileSize(file.sizeBytes)}</span>
        {file.truncated && <span className="file-view__badge file-view__badge--warn">{t('file.truncated')}</span>}
      </div>
      {file.binary ? (
        <div className="file-content__empty">{t('file.binary')}</div>
      ) : (
        <CodeView text={file.content ?? ''} fromLine={file.fromLine ?? 1} />
      )}
    </div>
  );
};

const FileContent = ({ content, loading, onNavigate }) => {
  const { t } = useTranslation('files');

  const path = content?.path ?? '';

  return (
    <div className="file-content">
      <Breadcrumb path={path} onNavigate={onNavigate} />
      <div className="file-content__body">
        {loading && <div className="file-content__empty">{t('loading')}</div>}
        {!loading && content?.type === 'directory' && (
          <DirectoryListing nodes={content.nodes} onNavigate={onNavigate} />
        )}
        {!loading && content?.type === 'file' && <FileView file={content.file} />}
        {!loading && content?.type === 'not-found' && <div className="file-content__empty">{t('file.notFound')}</div>}
        {!loading && content?.type === 'error' && <div className="file-content__empty">{t('file.loadError')}</div>}
      </div>
    </div>
  );
};

export default FileContent;
