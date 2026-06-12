import React, { useState } from 'react';
import SettingsShell, { SettingsContentHead, SettingsSection } from '../common/SettingsShell';
import { IconPlus, IconEdit, IconTrash } from '../knowledgeBasePanel/icons';
import { IconFileText, IconMessage, IconSliders } from '../common/menuIcons';
import './settingsPanel.css';

const GROUPS = [
  { key: 'prompt', label: 'Системный промпт', icon: <IconFileText size={16} /> },
  { key: 'phrases', label: 'Библиотека фраз', icon: <IconMessage size={16} /> },
  { key: 'models', label: 'Модели', icon: <IconSliders size={16} /> },
];

// Заглушки — на бэке: phrase-таблица (CRUD /api/phrases), settings.role,
// GET /api/chats/models.
const DEFAULT_PROMPT =
  'Ты — ассистент базы знаний проекта. Отвечай кратко, ссылайся на документы через /?doc=ID. ' +
  'Для вопросов по коду используй Git-инструменты.';

const PHRASES = [
  { id: 1, category: 'Анализ', label: 'История коммитов', text: 'Покажи историю коммитов файла и объясни изменения' },
  { id: 2, category: 'Коммиты', label: 'Имя коммита', text: 'Опиши незакоммиченные изменения и предложи имя коммита' },
  { id: 3, category: 'Документы', label: 'Черновик документа', text: 'Создай документ-черновик по теме с разделами' },
];

const MODELS = [
  { id: 'deepseek-v4-pro', isDefault: true },
  { id: 'qwen3-coder', isDefault: false },
  { id: 'gpt-5-mini', isDefault: false },
];

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

// ─── Группа: библиотека фраз ──────────────────────────────────────────────────

const PhrasesGroup = () => (
  <>
    <SettingsContentHead title="Библиотека фраз" subtitle="Готовые подсказки над полем ввода в пустом чате" />
    <div className="settings-content__body">
      <SettingsSection
        label="Фразы"
        action={
          <button className="set-btn set-btn--ghost set-btn--sm">
            <IconPlus /> Добавить
          </button>
        }
        rows
      >
        {PHRASES.map((p) => (
          <div key={p.id} className="phrase-row">
            <span className="phrase-pill">{p.category}</span>
            <div className="phrase-row__text">
              <div className="phrase-row__label">{p.label}</div>
              <div className="phrase-row__body">{p.text}</div>
            </div>
            <button className="set-icon-btn" title="Редактировать">
              <IconEdit />
            </button>
            <button className="set-icon-btn set-icon-btn--danger" title="Удалить">
              <IconTrash />
            </button>
          </div>
        ))}
      </SettingsSection>

      <p className="set-hint">
        Раньше фразы жили в коде (<code>GIT_PHRASES</code>). Здесь — единый список из БД: правится в настройках,
        показывается в пустом чате компонентом <code>GitPhrases</code>.
      </p>
    </div>
  </>
);

// ─── Группа: модели ───────────────────────────────────────────────────────────

const ModelsGroup = () => {
  const [defaultModel, setDefaultModel] = useState(MODELS.find((m) => m.isDefault)?.id);
  return (
    <>
      <SettingsContentHead title="Модели" subtitle="Доступные модели и модель по умолчанию для новых чатов" />
      <div className="settings-content__body">
        <SettingsSection label="По умолчанию">
          <div className="set-row">
            <span className="set-row__label">Модель для новых чатов</span>
            <select className="set-select" value={defaultModel} onChange={(e) => setDefaultModel(e.target.value)}>
              {MODELS.map((m) => (
                <option key={m.id} value={m.id}>
                  {m.id}
                </option>
              ))}
            </select>
          </div>
        </SettingsSection>

        <SettingsSection label="Доступные модели" rows>
          {MODELS.map((m) => (
            <div key={m.id} className="model-row">
              <span className="model-row__name">{m.id}</span>
              {m.id === defaultModel && <span className="model-row__badge">по умолчанию</span>}
            </div>
          ))}
        </SettingsSection>

        <p className="set-hint">
          Список приходит из <code>GET /api/chats/models</code> (<code>ChatModelProperties</code>). Модель по умолчанию
          применяется к черновику нового чата.
        </p>
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
      {group === 'phrases' && <PhrasesGroup />}
      {group === 'models' && <ModelsGroup />}
    </SettingsShell>
  );
};

export default SettingsPanel;
