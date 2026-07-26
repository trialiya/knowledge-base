import React from 'react';
import AiSummarySection from './AiSummarySection';
import SummarySection from './SummarySection';
import ContentsTable from './ContentsTable';
import DetailInfo from './DetailInfo';
import AttachmentPanel from '../common/AttachmentPanel';
import { IconSparkle, IconPaperclip, IconList, IconInfo } from '../../icons';
import { DOC_TAB } from '../../constants/docTabs';
import { OWNER_TYPE } from '../../constants/ownerType';

/**
 * Вкладки правой панели для выбранного узла базы знаний.
 *
 * Раньше это были вкладки ЦЕНТРА (DetailTabs): «Summary», «Содержимое»,
 * «Состав», «Вложения» — и всё «о документе» отнимало место у самого документа.
 * Теперь центр занят редактором содержимого, а описание, состав папки, вложения
 * и метаданные живут здесь, в общей правой панели (свёрнута по умолчанию).
 *
 * Это функция-сборщик, а не компонент: WorkspaceLayout принимает вкладки
 * массивом ({ key, label, icon, badge, content }) и сам решает, показать их
 * рельсом или раскрытой панелью.
 */
export function buildDetailTabs({
  node,
  t,
  tree,
  onNavigate,
  onSummarize,
  onExpandAbout,
  attachmentCount,
  onAttachmentCountChange,
  folderChildren = [],
}) {
  const isFolder = node.type === 'folder';

  const tabs = [
    // «Инфо» — первая вкладка во всех разделах: сначала «что это за объект»,
    // потом всё остальное о нём.
    {
      key: DOC_TAB.INFO,
      label: t('tabs.info'),
      icon: <IconInfo size={15} />,
      content: <DetailInfo node={node} />,
    },
    {
      key: DOC_TAB.SUMMARY,
      label: t('tabs.summary'),
      icon: <IconSparkle size={15} />,
      content: (
        <>
          {/* AI-описание есть только у документов — папки не суммаризируются. */}
          {!isFolder && <AiSummarySection node={node} onSummarize={onSummarize} />}
          <SummarySection
            label={t('detail.about')}
            description={node.description}
            onExpand={onExpandAbout}
            tree={tree}
            onNavigate={onNavigate}
            copyable
          />
        </>
      ),
    },
  ];

  if (isFolder) {
    tabs.push({
      key: DOC_TAB.CONTENTS,
      label: t('tabs.contents'),
      icon: <IconList size={15} />,
      badge: folderChildren.length,
      content:
        folderChildren.length === 0 ? (
          <p className="empty-tab">{t('detail.folderEmpty')}</p>
        ) : (
          <ContentsTable items={folderChildren} onNavigate={onNavigate} />
        ),
    });
  }

  tabs.push({
    key: DOC_TAB.ATTACHMENTS,
    label: t('tabs.attachments'),
    icon: <IconPaperclip size={15} />,
    badge: attachmentCount,
    content: (
      <AttachmentPanel
        key={node.id}
        ownerType={OWNER_TYPE.DOCUMENT}
        ownerId={node.id}
        onCountChange={onAttachmentCountChange}
      />
    ),
  });

  return tabs;
}
