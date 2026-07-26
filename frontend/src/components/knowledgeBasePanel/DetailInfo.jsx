import React from 'react';
import { useTranslation } from 'react-i18next';
import InfoList from '../common/InfoList';
import { formatDateTime } from '../../utils/formatting';

/**
 * Вкладка «Инфо» правой панели: метаданные узла базы знаний.
 *
 * Показываем только те поля, которые реально пришли с бэка (набор отличается у
 * дерева и у полного GET /documents/{id}), — пустых строк «—» быть не должно;
 * отсев пустых значений делает InfoList.
 */
const DetailInfo = ({ node }) => {
  const { t, i18n } = useTranslation('knowledgeBase');

  const rows = [
    { label: t('info.type'), value: node.type === 'folder' ? t('info.typeFolder') : t('info.typeDocument') },
    { label: t('info.created'), value: formatDateTime(node.createdAt, i18n.language) },
    { label: t('info.updated'), value: formatDateTime(node.updatedAt, i18n.language) },
    { label: t('info.version'), value: node.version != null ? String(node.version) : null },
    {
      label: t('info.descriptionVersion'),
      value: node.descriptionVersion != null ? String(node.descriptionVersion) : null,
    },
    { label: t('info.id'), value: String(node.id), mono: true },
  ];

  return <InfoList rows={rows} note={node.system ? t('info.systemNote') : null} />;
};

export default DetailInfo;
