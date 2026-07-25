import React from 'react';
import { useTranslation } from 'react-i18next';

/**
 * Вкладка «Инфо» правой панели: метаданные узла базы знаний.
 *
 * Показываем только те поля, которые реально пришли с бэка (набор отличается у
 * дерева и у полного GET /documents/{id}), — пустых строк «—» быть не должно.
 */
const DetailInfo = ({ node }) => {
  const { t, i18n } = useTranslation('knowledgeBase');

  const fmt = (raw) => (raw ? new Date(raw).toLocaleString(i18n.language) : null);

  const rows = [
    [t('info.type'), node.type === 'folder' ? t('info.typeFolder') : t('info.typeDocument')],
    [t('info.created'), fmt(node.createdAt)],
    [t('info.updated'), fmt(node.updatedAt)],
    [t('info.version'), node.version != null ? String(node.version) : null],
    [t('info.descriptionVersion'), node.descriptionVersion != null ? String(node.descriptionVersion) : null],
    [t('info.id'), String(node.id)],
  ].filter(([, value]) => value != null && value !== '');

  return (
    <div className="detail-info">
      <dl className="detail-info__list">
        {rows.map(([label, value]) => (
          <div className="detail-info__row" key={label}>
            <dt className="detail-info__label">{label}</dt>
            <dd className="detail-info__value">{value}</dd>
          </div>
        ))}
      </dl>
      {node.system && <p className="detail-info__note">{t('info.systemNote')}</p>}
    </div>
  );
};

export default DetailInfo;
