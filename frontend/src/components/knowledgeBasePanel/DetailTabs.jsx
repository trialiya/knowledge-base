import React from 'react';
import { useTranslation } from 'react-i18next';

/**
 * Tab bar shared by FolderDetail and DocumentDetail.
 *
 * props:
 *   tabs            — [{ key, labelKey }]
 *   tab             — active tab key
 *   onTabChange     — (key) => void
 *   attachmentCount — number shown as a badge on the 'attachments' tab
 */
const DetailTabs = ({ tabs, tab, onTabChange, attachmentCount }) => {
  const { t } = useTranslation('knowledgeBase');
  return (
    <div className="detail-tabs">
      {tabs.map(({ key, labelKey }) => (
        <button
          key={key}
          className={`detail-tab ${tab === key ? 'detail-tab--active' : ''}`}
          onClick={() => onTabChange(key)}
        >
          {t(labelKey)}
          {key === 'attachments' && attachmentCount > 0 && <span className="detail-tab__count">{attachmentCount}</span>}
        </button>
      ))}
    </div>
  );
};

export default DetailTabs;
