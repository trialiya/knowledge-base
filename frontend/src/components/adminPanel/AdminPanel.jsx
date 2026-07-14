import React, { useState } from 'react';
import SettingsShell, { SettingsContentHead, SettingsSection } from '../common/SettingsShell';
import { IconRefresh, IconDatabase, IconDownload, IconTool, IconEraser } from '../../icons';
import BulkOperations from './BulkOperations';
import './adminPanel.css';

// Заглушечные данные — на бэке заменить вызовом GET /api/admin/index/stats и т.п.
const STATS = {
  documents: 248,
  chunks: 3412,
  stale: 17,
  lastRun: '10.06.2026, 22:14',
  lastDuration: '3 мин 12 с',
};

const GROUPS = [
  { key: 'index', label: 'Семантический индекс', icon: <IconDatabase size={16} /> },
  { key: 'bulk', label: 'Массовые операции', icon: <IconDownload size={16} /> },
  { key: 'maintenance', label: 'Обслуживание', icon: <IconTool size={16} /> },
];

const nf = (n) => n.toLocaleString('ru-RU');

// ─── Группа: семантический индекс ─────────────────────────────────────────────

const IndexGroup = () => (
  <>
    <SettingsContentHead title="Семантический индекс" subtitle="Состояние эмбеддингов и переиндексация базы знаний" />
    <div className="settings-content__body">
      <SettingsSection label="Состояние">
        <div className="admin-stats">
          <div className="admin-stat">
            <div className="admin-stat__label">Документов</div>
            <div className="admin-stat__value">{nf(STATS.documents)}</div>
          </div>
          <div className="admin-stat">
            <div className="admin-stat__label">Чанков эмбеддингов</div>
            <div className="admin-stat__value">{nf(STATS.chunks)}</div>
          </div>
          <div className="admin-stat">
            <div className="admin-stat__label">Устаревших</div>
            <div className="admin-stat__value admin-stat__value--warn">{nf(STATS.stale)}</div>
          </div>
        </div>

        <div className="admin-status">
          <span className="admin-badge admin-badge--ok">готово</span>
          <span>
            Последняя индексация: {STATS.lastRun} · {STATS.lastDuration}
          </span>
        </div>

        <div className="set-actions">
          <button className="set-btn set-btn--primary">
            <IconRefresh size={14} /> Переиндексировать всё
          </button>
          <button className="set-btn set-btn--ghost">Только устаревшие ({STATS.stale})</button>
        </div>
      </SettingsSection>
    </div>
  </>
);

// ─── Группа: обслуживание ─────────────────────────────────────────────────────

const MaintenanceGroup = () => (
  <>
    <SettingsContentHead title="Обслуживание" subtitle="Служебные операции с кэшами и индексами" />
    <div className="settings-content__body">
      <SettingsSection label="Кэши" rows>
        <div className="set-op">
          <span className="set-op__icon">
            <IconEraser size={18} />
          </span>
          <div className="set-op__text">
            <div className="set-op__title">Очистить кэш эмбеддингов</div>
            <div className="set-op__desc">Кэш пересоздастся при следующей индексации</div>
          </div>
          <button className="set-btn set-btn--danger">Очистить</button>
        </div>
      </SettingsSection>

      <p className="set-hint">
        Операции в этом разделе необратимы и могут временно замедлить поиск, пока индексы перестраиваются.
      </p>
    </div>
  </>
);

// ─── AdminPanel ───────────────────────────────────────────────────────────────

const AdminPanel = () => {
  const [group, setGroup] = useState('index');

  return (
    <SettingsShell title="Администрирование" groups={GROUPS} activeKey={group} onSelect={setGroup}>
      {group === 'index' && <IndexGroup />}
      {group === 'bulk' && <BulkOperations />}
      {group === 'maintenance' && <MaintenanceGroup />}
    </SettingsShell>
  );
};

export default AdminPanel;
