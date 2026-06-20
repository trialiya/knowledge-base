import React, { useEffect, useState } from 'react';
import SettingsShell, { SettingsContentHead, SettingsSection } from '../common/SettingsShell';
import { IconFileText, IconMessage, IconSliders } from '../common/menuIcons';
import PhrasesSettings from './PhrasesSettings';
import settingsApi from '../../api/settingsApi';
import './settingsPanel.css';

const GROUPS = [
  { key: 'prompt', label: 'Системный промпт', icon: <IconFileText size={16} /> },
  { key: 'phrases', label: 'Библиотека фраз', icon: <IconMessage size={16} /> },
  { key: 'models', label: 'Модели', icon: <IconSliders size={16} /> },
];

// Заглушка — на бэке: settings.role, POST /api/settings/prompt (TODO).
const DEFAULT_PROMPT =
  'Ты — ассистент базы знаний проекта. Отвечай кратко, ссылайся на документы через /?doc=ID. ' +
  'Для вопросов по коду используй Git-инструменты.';

// ─── Группа: системный промпт ─────────────────────────────────────────────────

const PromptGroup = () => {
  const [value, setValue] = useState(DEFAULT_PROMPT);
  return (
    <>
      <SettingsContentHead title="Системный промпт" subtitle="Глобальная роль ассистента — блок {role}" />
      <div className="settings-content__body">
        <SettingsSection label="Роль ассистента">
          <textarea className="set-textarea" rows={5} value={value} onChange={(e) => setValue(e.target.value)} />
          <p className="set-hint">
            Подставляется в Spring AI <code>defaultSystem</code> на месте <code>{'{role}'}</code>. Дополнение
            конкретного чата — <code>{'{userExtra}'}</code> — задаётся в шапке самого чата, а не здесь.
          </p>
          <div className="set-actions">
            <button className="set-btn set-btn--primary">Сохранить</button>
            <button className="set-btn set-btn--ghost" onClick={() => setValue(DEFAULT_PROMPT)}>
              Сбросить
            </button>
          </div>
        </SettingsSection>
      </div>
    </>
  );
};

// ─── Группа: конфигурация AI-моделей ─────────────────────────────────────────

const ModelsGroup = () => {
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
        if (!cancelled) setError(e.message || 'Ошибка загрузки конфигурации');
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const head = (
    <SettingsContentHead
      title="Конфигурация AI-моделей"
      subtitle="Параметры вызова, основная модель, агент поиска, сжатие контекста"
    />
  );

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
          <p className="ai-config-loading">Загрузка…</p>
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
        <SettingsSection label="Общее">
          <div className="set-row">
            <span className="set-row__label">Макс. токенов ответа</span>
            <span className="set-row__value">{chat.options?.maxTokens?.toLocaleString('ru')}</span>
          </div>
          <div className="set-row">
            <span className="set-row__label">Temperature</span>
            <span className="set-row__value">{chat.options?.temperature}</span>
          </div>
          <div className="set-row">
            <span className="set-row__label">Top-p</span>
            <span className="set-row__value">{chat.options?.topP}</span>
          </div>
          <div className="set-row">
            <span className="set-row__label">Окно памяти</span>
            <span className="set-row__value">50 сообщений</span>
          </div>
        </SettingsSection>

        {/* ── Основная модель ── */}
        <SettingsSection label="Основная модель (Чат)">
          <div className="set-row">
            <span className="set-row__label">Модель</span>
            <span className="set-row__value">{defaultId}</span>
            {chat.defaultModel?.label && chat.defaultModel.label !== 'Default' && (
              <span className="model-row__badge">{chat.defaultModel.label}</span>
            )}
          </div>
        </SettingsSection>

        {chat.models?.length > 0 && (
          <SettingsSection label="Доступные модели" rows>
            {chat.models.map((m) => (
              <div key={m.id} className="model-row">
                <span className="model-row__name">{m.id}</span>
                {m.label && m.label !== m.id && <span className="model-row__label">{m.label}</span>}
                {m.id === defaultId && <span className="model-row__badge">по умолчанию</span>}
              </div>
            ))}
          </SettingsSection>
        )}

        {/* ── Агент поиска ── */}
        <SettingsSection label="Агент поиска (searchCodebase)">
          <div className="set-row">
            <span className="set-row__label">Статус</span>
            <span className={`status-badge status-badge--${searchCodebase.enabled ? 'on' : 'off'}`}>
              {searchCodebase.enabled ? 'включён' : 'отключён'}
            </span>
          </div>
          <div className="set-row">
            <span className="set-row__label">Модель</span>
            <span className="set-row__value">
              {subagentSameModel ? `= основная (${defaultId})` : searchCodebase.modelId}
            </span>
          </div>
          <div className="set-row">
            <span className="set-row__label">Макс. токенов на вызов</span>
            <span className="set-row__value">{searchCodebase.maxTokens.toLocaleString('ru')}</span>
          </div>
          <div className="set-row">
            <span className="set-row__label">Макс. итераций инструментов</span>
            <span className="set-row__value">{searchCodebase.maxIterations}</span>
          </div>
        </SettingsSection>

        {/* ── Сжатие контекста ── */}
        <SettingsSection label="Сжатие контекста (SummarizeService)">
          <div className="set-row">
            <span className="set-row__label">Модель</span>
            <span className="set-row__value">= основная ({defaultId})</span>
          </div>
          <div className="set-row">
            <span className="set-row__label">Порог токенов</span>
            <span className="set-row__value">{summarize.tokenThreshold.toLocaleString('ru')} токенов</span>
          </div>
          <div className="set-row">
            <span className="set-row__label">Порог сообщений</span>
            <span className="set-row__value">{summarize.messageCountThreshold} сообщений</span>
          </div>
          <div className="set-row">
            <span className="set-row__label">Буфер живых сообщений</span>
            <span className="set-row__value">{summarize.overlapMessages} сообщений</span>
          </div>
        </SettingsSection>
      </div>
    </>
  );
};

// ─── SettingsPanel ────────────────────────────────────────────────────────────

const SettingsPanel = () => {
  const [group, setGroup] = useState('prompt');

  return (
    <SettingsShell title="Настройки" groups={GROUPS} activeKey={group} onSelect={setGroup}>
      {group === 'prompt' && <PromptGroup />}
      {group === 'phrases' && <PhrasesSettings />}
      {group === 'models' && <ModelsGroup />}
    </SettingsShell>
  );
};

export default SettingsPanel;
