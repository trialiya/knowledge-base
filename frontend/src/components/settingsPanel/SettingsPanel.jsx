import { useTranslation } from 'react-i18next';
import SettingsShell from '@/components/common/layout/SettingsShell';
import { IconMessage, IconSliders, IconSearch, IconTool, IconCodeBlock } from '@/icons/index';
import { SETTINGS_GROUP, defaultGroup } from '@/constants/settingsGroups';
import PhrasesSettings from './PhrasesSettings';
import ModelsSettings from './ModelsSettings';
import SearchSettings from './SearchSettings';
import ToolsSettings from './ToolsSettings';
import ScriptsSettings from './ScriptsSettings';
import './settingsPanel.css';

// Группы-снимки конфигурации (модели, поиск, инструменты) лежат в соседних
// файлах: панель — только список групп, содержимое каждой сложилось в отдельный
// экран на десяток секций.
//
// Выбранная группа живёт в адресе (`/settings/<группа>`, пусто — дефолтная),
// поэтому она приходит пропом, а не хранится здесь.

const SettingsPanel = ({ group, onGroupChange, panels }) => {
  const { t } = useTranslation('settings');
  const active = group || defaultGroup('settings');

  const groups = [
    { key: SETTINGS_GROUP.PHRASES, label: t('nav.phrases'), icon: <IconMessage size={16} /> },
    { key: SETTINGS_GROUP.MODELS, label: t('nav.models'), icon: <IconSliders size={16} /> },
    { key: SETTINGS_GROUP.SEARCH, label: t('nav.search'), icon: <IconSearch size={16} /> },
    { key: SETTINGS_GROUP.TOOLS, label: t('nav.tools'), icon: <IconTool size={16} /> },
    { key: SETTINGS_GROUP.SCRIPTS, label: t('nav.scripts'), icon: <IconCodeBlock size={16} /> },
  ];

  return (
    <SettingsShell
      title={t('nav.title')}
      groups={groups}
      activeKey={active}
      onSelect={(key) => onGroupChange('settings', key)}
      panels={panels}
    >
      {active === SETTINGS_GROUP.PHRASES && <PhrasesSettings />}
      {active === SETTINGS_GROUP.MODELS && <ModelsSettings />}
      {active === SETTINGS_GROUP.SEARCH && <SearchSettings />}
      {active === SETTINGS_GROUP.TOOLS && <ToolsSettings />}
      {active === SETTINGS_GROUP.SCRIPTS && <ScriptsSettings />}
    </SettingsShell>
  );
};

export default SettingsPanel;
