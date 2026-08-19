import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import SettingsShell from '../common/layout/SettingsShell';
import { IconDatabase, IconDownload, IconInfo } from '../../icons';
import BulkOperations from './BulkOperations';
import IndexOperations from './IndexOperations';
import SystemInfo from './SystemInfo';
import './adminPanel.css';

const AdminPanel = ({ panels }) => {
  const { t } = useTranslation('settings');
  const [group, setGroup] = useState('index');

  const groups = [
    { key: 'index', label: t('admin.nav.index'), icon: <IconDatabase size={16} /> },
    { key: 'bulk', label: t('admin.nav.bulk'), icon: <IconDownload size={16} /> },
    { key: 'system', label: t('admin.nav.system'), icon: <IconInfo size={16} /> },
  ];

  return (
    <SettingsShell title={t('admin.nav.title')} groups={groups} activeKey={group} onSelect={setGroup} panels={panels}>
      {group === 'index' && <IndexOperations />}
      {group === 'bulk' && <BulkOperations />}
      {group === 'system' && <SystemInfo />}
    </SettingsShell>
  );
};

export default AdminPanel;
