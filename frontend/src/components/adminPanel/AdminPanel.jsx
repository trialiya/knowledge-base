import React, { useState } from 'react';
import { useTranslation } from 'react-i18next';
import SettingsShell, { SettingsContentHead, SettingsSection } from '../common/SettingsShell';
import { IconRefresh, IconDatabase, IconDownload } from '../../icons';
import documentsApi from '../../api/documentsApi';
import BulkOperations from './BulkOperations';
import './adminPanel.css';

// ─── Группа: семантический индекс ─────────────────────────────────────────────
// Раньше здесь был макет с фейковой статистикой и кнопками без обработчиков;
// теперь — только реально работающая операция: полная переиндексация через
// POST /api/documents/admin/reindex.

const IndexGroup = () => {
  const { t } = useTranslation('settings');

  // idle | running | done | error
  const [state, setState] = useState('idle');

  const runReindex = async () => {
    if (state === 'running') return;
    setState('running');
    try {
      await documentsApi.reindex();
      setState('done');
    } catch {
      setState('error');
    }
  };

  return (
    <>
      <SettingsContentHead title={t('admin.index.title')} subtitle={t('admin.index.subtitle')} />
      <div className="settings-content__body">
        <SettingsSection label={t('admin.index.sectionLabel')} rows>
          <div className="set-op">
            <span className="set-op__icon">
              <IconRefresh size={18} />
            </span>
            <div className="set-op__text">
              <div className="set-op__title">{t('admin.index.reindex.title')}</div>
              <div className="set-op__desc">{t('admin.index.reindex.desc')}</div>
              {state === 'done' && (
                <div className="admin-status admin-status--inline">
                  <span className="admin-badge admin-badge--ok">{t('admin.index.reindex.doneBadge')}</span>
                  <span>{t('admin.index.reindex.done')}</span>
                </div>
              )}
              {state === 'error' && (
                <div className="admin-status admin-status--inline">
                  <span className="admin-badge admin-badge--error">{t('admin.index.reindex.errorBadge')}</span>
                  <span>{t('admin.index.reindex.error')}</span>
                </div>
              )}
            </div>
            <button className="set-btn set-btn--primary" onClick={runReindex} disabled={state === 'running'}>
              {state === 'running' ? t('admin.index.reindex.running') : t('admin.index.reindex.run')}
            </button>
          </div>
        </SettingsSection>
      </div>
    </>
  );
};

// ─── AdminPanel ───────────────────────────────────────────────────────────────

const AdminPanel = () => {
  const { t } = useTranslation('settings');
  const [group, setGroup] = useState('index');

  const groups = [
    { key: 'index', label: t('admin.nav.index'), icon: <IconDatabase size={16} /> },
    { key: 'bulk', label: t('admin.nav.bulk'), icon: <IconDownload size={16} /> },
  ];

  return (
    <SettingsShell title={t('admin.nav.title')} groups={groups} activeKey={group} onSelect={setGroup}>
      {group === 'index' && <IndexGroup />}
      {group === 'bulk' && <BulkOperations />}
    </SettingsShell>
  );
};

export default AdminPanel;
