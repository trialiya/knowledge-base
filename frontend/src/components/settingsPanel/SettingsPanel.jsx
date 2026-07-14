import React, { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import SettingsShell, { SettingsContentHead, SettingsSection } from '../common/SettingsShell';
import { IconMessage, IconSliders } from '../../icons';
import PhrasesSettings from './PhrasesSettings';
import settingsApi from '../../api/settingsApi';
import './settingsPanel.css';

// Раздел «Системный промпт» удалён: это был макет без бэкенд-эндпоинта
// (кнопка «Сохранить» ничего не делала). Вернуть, когда появится
// settings.role / POST /api/settings/prompt.

// ─── Группа: конфигурация AI-моделей ─────────────────────────────────────────

const ModelsGroup = () => {
  const { t } = useTranslation('settings');
  const [config, setConfig] = useState(null);
  const [error, setError] = useState(null);

  useEffect(() => {
    let cancelled = false;
    settingsApi
      .getAiConfig()
      .then((data) => {
        if (!cancelled) setConfig(data);
      })
      .catch((e) => {
        if (!cancelled) setError(e.message || t('models.errorLoading'));
      });
    return () => {
      cancelled = true;
    };
  }, [t]);

  const head = <SettingsContentHead title={t('models.title')} subtitle={t('models.subtitle')} />;

  if (error) {
    return (
      <>
        {head}
        <div className="settings-content__body">
          <p className="ai-config-error">{error}</p>
        </div>
      </>
    );
  }

  if (!config) {
    return (
      <>
        {head}
        <div className="settings-content__body">
          <p className="ai-config-loading">{t('models.loading')}</p>
        </div>
      </>
    );
  }

  const { chat, searchCodebase, summarize } = config;
  const defaultId = chat.defaultModel?.id;
  const subagentSameModel = searchCodebase.modelId === defaultId;

  return (
    <>
      {head}
      <div className="settings-content__body">
        {/* ── Общее ── */}
        <SettingsSection label={t('models.general.label')}>
          <div className="set-row">
            <span className="set-row__label">{t('models.general.maxTokens')}</span>
            <span className="set-row__value">{chat.options?.maxTokens?.toLocaleString()}</span>
          </div>
          <div className="set-row">
            <span className="set-row__label">{t('models.general.temperature')}</span>
            <span className="set-row__value">{chat.options?.temperature}</span>
          </div>
          <div className="set-row">
            <span className="set-row__label">{t('models.general.topP')}</span>
            <span className="set-row__value">{chat.options?.topP}</span>
          </div>
          <div className="set-row">
            <span className="set-row__label">{t('models.general.memoryWindow')}</span>
            <span className="set-row__value">{t('models.general.memoryWindowValue')}</span>
          </div>
        </SettingsSection>

        {/* ── Основная модель ── */}
        <SettingsSection label={t('models.chat.label')}>
          <div className="set-row">
            <span className="set-row__label">{t('models.chat.model')}</span>
            <span className="set-row__value">{defaultId}</span>
            {chat.defaultModel?.label && chat.defaultModel.label !== 'Default' && (
              <span className="model-row__badge">{chat.defaultModel.label}</span>
            )}
          </div>
        </SettingsSection>

        {chat.models?.length > 0 && (
          <SettingsSection label={t('models.available.label')} rows>
            {chat.models.map((m) => (
              <div key={m.id} className="model-row">
                <span className="model-row__name">{m.id}</span>
                {m.label && m.label !== m.id && <span className="model-row__label">{m.label}</span>}
                {m.id === defaultId && <span className="model-row__badge">{t('models.available.defaultBadge')}</span>}
              </div>
            ))}
          </SettingsSection>
        )}

        {/* ── Агент поиска ── */}
        <SettingsSection label={t('models.searchCodebase.label')}>
          <div className="set-row">
            <span className="set-row__label">{t('models.searchCodebase.status')}</span>
            <span className={`status-badge status-badge--${searchCodebase.enabled ? 'on' : 'off'}`}>
              {searchCodebase.enabled ? t('models.searchCodebase.statusOn') : t('models.searchCodebase.statusOff')}
            </span>
          </div>
          <div className="set-row">
            <span className="set-row__label">{t('models.searchCodebase.model')}</span>
            <span className="set-row__value">
              {subagentSameModel ? t('models.searchCodebase.modelSameAs', { id: defaultId }) : searchCodebase.modelId}
            </span>
          </div>
          <div className="set-row">
            <span className="set-row__label">{t('models.searchCodebase.maxTokens')}</span>
            <span className="set-row__value">
              {t('models.searchCodebase.maxTokensValue', { count: searchCodebase.maxTokens.toLocaleString() })}
            </span>
          </div>
          <div className="set-row">
            <span className="set-row__label">{t('models.searchCodebase.maxIterations')}</span>
            <span className="set-row__value">{searchCodebase.maxIterations}</span>
          </div>
        </SettingsSection>

        {/* ── Сжатие контекста ── */}
        <SettingsSection label={t('models.summarize.label')}>
          <div className="set-row">
            <span className="set-row__label">{t('models.summarize.model')}</span>
            <span className="set-row__value">{t('models.summarize.modelSameAs', { id: defaultId })}</span>
          </div>
          <div className="set-row">
            <span className="set-row__label">{t('models.summarize.tokenThreshold')}</span>
            <span className="set-row__value">
              {t('models.summarize.tokensValue', { count: summarize.tokenThreshold.toLocaleString() })}
            </span>
          </div>
          <div className="set-row">
            <span className="set-row__label">{t('models.summarize.messageThreshold')}</span>
            <span className="set-row__value">
              {t('models.summarize.messagesValue', { count: summarize.messageCountThreshold })}
            </span>
          </div>
          <div className="set-row">
            <span className="set-row__label">{t('models.summarize.overlap')}</span>
            <span className="set-row__value">
              {t('models.summarize.messagesValue', { count: summarize.overlapMessages })}
            </span>
          </div>
        </SettingsSection>
      </div>
    </>
  );
};

// ─── SettingsPanel ────────────────────────────────────────────────────────────

const SettingsPanel = () => {
  const { t } = useTranslation('settings');
  const [group, setGroup] = useState('phrases');

  const groups = [
    { key: 'phrases', label: t('nav.phrases'), icon: <IconMessage size={16} /> },
    { key: 'models', label: t('nav.models'), icon: <IconSliders size={16} /> },
  ];

  return (
    <SettingsShell title={t('nav.title')} groups={groups} activeKey={group} onSelect={setGroup}>
      {group === 'phrases' && <PhrasesSettings />}
      {group === 'models' && <ModelsGroup />}
    </SettingsShell>
  );
};

export default SettingsPanel;
