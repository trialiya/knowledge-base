import React, { useState, useEffect } from 'react';
import DetailHeader from './DetailHeader';
import ContentsTable from './ContentsTable';
import SummarySection from './SummarySection';
import MarkdownEditor from './MarkdownEditor';
import AttachmentPanel from './AttachmentPanel';

const TABS = [
  { key: 'summary', label: 'Summary' },
  { key: 'content', label: 'Content' },
  { key: 'contents', label: 'Contents' },
  { key: 'attachments', label: 'Attachments' },
];

/**
 * Fetches ALL children of a folder from the paginated endpoint (large page).
 * This is used by FolderDetail independently of the tree's lazy-loading.
 */
const fetchAllChildren = async (parentId) => {
  try {
    const params = new URLSearchParams({ parentId, page: '0', size: '1000' });
    const res = await fetch(`/api/documents/children?${params}`);
    if (!res.ok) return [];
    const paged = await res.json();
    return Array.isArray(paged.items) ? paged.items : [];
  } catch {
    return [];
  }
};

const FolderDetail = ({ node, path, tab, onTabChange, onUpdate, onDelete, onNavigate, onRename }) => {
  const [attachmentCount, setAttachmentCount] = useState(0);
  // Own children state — loaded independently from the tree
  const [allChildren, setAllChildren] = useState(node.children || []);
  const [childrenLoading, setChildrenLoading] = useState(false);

  // Load all children when the folder changes or when node.children update
  useEffect(() => {
    let cancelled = false;

    // If the tree already has children loaded, use them as a starting point
    if (node.children && node.children.length > 0) {
      setAllChildren(node.children);
    }

    // Always fetch the full list from the server to ensure we have everything
    if (node.id && node.type === 'folder') {
      setChildrenLoading(true);
      fetchAllChildren(node.id).then((fetched) => {
        if (!cancelled) {
          // Use fetched data if it has more items than what we got from the tree
          if (fetched.length > 0) {
            setAllChildren(fetched);
          }
          setChildrenLoading(false);
        }
      });
    }

    return () => {
      cancelled = true;
    };
  }, [node.id]); // eslint-disable-line react-hooks/exhaustive-deps

  // Also sync if the tree pushes updated children (e.g. after create/delete)
  useEffect(() => {
    if (node.children && node.children.length > allChildren.length) {
      setAllChildren(node.children);
    }
  }, [node.children?.length]); // eslint-disable-line react-hooks/exhaustive-deps

  return (
    <div className="detail-panel">
      <DetailHeader node={node} path={path} onNavigate={onNavigate} onRename={onRename} onDelete={onDelete} />

      <div className="detail-tabs">
        {TABS.map(({ key, label }) => (
          <button
            key={key}
            className={`detail-tab ${tab === key ? 'detail-tab--active' : ''}`}
            onClick={() => onTabChange(key)}
          >
            {label}
            {key === 'attachments' && attachmentCount > 0 && (
              <span className="detail-tab__count">{attachmentCount}</span>
            )}
          </button>
        ))}
      </div>

      <div className="detail-body">
        {tab === 'summary' && (
          <div className="summary-tab">
            <SummarySection label="About" description={node.description} onEdit={() => onTabChange('content')} />
            {allChildren.length > 0 && (
              <SummarySection label="Contents" showMoreBtn onMore={() => onTabChange('contents')}>
                <ContentsTable children={allChildren} onNavigate={onNavigate} />
              </SummarySection>
            )}
            <SummarySection label="Attachments" showMoreBtn onMore={() => onTabChange('attachments')}>
              <AttachmentPanel ownerType="document" ownerId={node.id} compact onCountChange={setAttachmentCount} />
            </SummarySection>
          </div>
        )}

        {tab === 'content' && (
          <MarkdownEditor
            value={node.description || ''}
            placeholder="Описание папки..."
            onSave={(val) => onUpdate(node.id, { description: val })}
          />
        )}

        {tab === 'contents' && (
          <div className="contents-tab">
            {childrenLoading && allChildren.length === 0 ? (
              <p className="empty-tab">Загрузка…</p>
            ) : allChildren.length === 0 ? (
              <p className="empty-tab">Папка пуста</p>
            ) : (
              <ContentsTable children={allChildren} onNavigate={onNavigate} />
            )}
          </div>
        )}

        {tab === 'attachments' && (
          <AttachmentPanel ownerType="document" ownerId={node.id} onCountChange={setAttachmentCount} />
        )}
      </div>
    </div>
  );
};

export default FolderDetail;
