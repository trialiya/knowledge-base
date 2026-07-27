import React, { useState } from 'react';
import { useTranslation } from 'react-i18next';
import SettingsShell from '../common/SettingsShell';
import { IconMessage, IconSliders, IconSearch, IconTool } from '../../icons';
import PhrasesSettings from './PhrasesSettings';
import ModelsSettings from './ModelsSettings';
import SearchSettings from './SearchSettings';
import ToolsSettings from './ToolsSettings';
import './settingsPanel.css';

// Раздел «Системный промпт» удалён: это был макет без бэкенд-эндпоинта
// (кнопка «Сохранить» ничего не делала). Вернуть, когда появится
// settings.role / POST /api/settings/prompt.
//
// Группы-снимки конфигурации (модели, поиск, инструменты) лежат в соседних
// файлах: панель — только список групп, содержимое каждой сложилось в отдельный
// экран на десяток секций.

const SettingsPanel = ({ panels }) => {
  const { t } = useTranslation('settings');
  const [group, setGroup] = useState('phrases');

  const groups = [
    { key: 'phrases', label: t('nav.phrases'), icon: <IconMessage size={16} /> },
    { key: 'models', label: t('nav.models'), icon: <IconSliders size={16} /> },
    { key: 'search', label: t('nav.search'), icon: <IconSearch size={16} /> },
    { key: 'tools', label: t('nav.tools'), icon: <IconTool size={16} /> },
  ];

  return (
    <SettingsShell title={t('nav.title')} groups={groups} activeKey={group} onSelect={setGroup} panels={panels}>
      {group === 'phrases' && <PhrasesSettings />}
      {group === 'models' && <ModelsSettings />}
      {group === 'search' && <SearchSettings />}
      {group === 'tools' && <ToolsSettings />}
    </SettingsShell>
  );
};

export default SettingsPanel;
