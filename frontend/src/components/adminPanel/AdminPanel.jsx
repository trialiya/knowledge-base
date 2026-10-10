import { useTranslation } from 'react-i18next';
import SettingsShell from '@/components/common/layout/SettingsShell';
import { IconDatabase, IconDownload, IconInfo } from '@/icons/index';
import { ADMIN_GROUP, defaultGroup } from '@/constants/settingsGroups';
import BulkOperations from './BulkOperations';
import IndexOperations from './IndexOperations';
import SystemInfo from './SystemInfo';
import './adminPanel.css';

// Выбранная группа живёт в адресе (`/admin/<группа>`, пусто — дефолтная),
// поэтому она приходит пропом, а не хранится здесь.
const AdminPanel = ({ group, onGroupChange, panels }) => {
  const { t } = useTranslation('settings');
  const active = group || defaultGroup('admin');

  const groups = [
    { key: ADMIN_GROUP.INDEX, label: t('admin.nav.index'), icon: <IconDatabase size={16} /> },
    { key: ADMIN_GROUP.BULK, label: t('admin.nav.bulk'), icon: <IconDownload size={16} /> },
    { key: ADMIN_GROUP.SYSTEM, label: t('admin.nav.system'), icon: <IconInfo size={16} /> },
  ];

  return (
    <SettingsShell
      title={t('admin.nav.title')}
      groups={groups}
      activeKey={active}
      onSelect={(key) => onGroupChange('admin', key)}
      panels={panels}
    >
      {active === ADMIN_GROUP.INDEX && <IndexOperations />}
      {active === ADMIN_GROUP.BULK && <BulkOperations />}
      {active === ADMIN_GROUP.SYSTEM && <SystemInfo />}
    </SettingsShell>
  );
};

export default AdminPanel;
