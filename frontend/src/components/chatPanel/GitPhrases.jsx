import React, { useState, useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import './gitPhrases.css';

// ─── Список фраз ───────────────────────────────────────────────────────────
// Тексты/лейблы/категории живут в i18n под chat.gitPhrases.*:
//   • category → ключ chat.gitPhrases.categories.<category>
//   • id       → ключи chat.gitPhrases.phrases.<id>.{label,text}
//
// ВАЖНО: в текстах фраз плейсхолдеры пишутся одинарными скобками — {файл}, {текст}.
// Двойные {{ }} зарезервированы под интерполяцию i18next и были бы вырезаны.
export const GIT_PHRASES = [
  // ── Анализ ───────────────────────────────────────────────────────────────
  { id: 'commitHistory', category: 'analysis' },
  { id: 'findCommitByText', category: 'analysis' },
  // ── Коммиты ──────────────────────────────────────────────────────────────
  { id: 'commitNameFromUncommitted', category: 'commits' },
];

// Git branch icon
const IconGitBranch = () => (
  <svg
    width="14"
    height="14"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    strokeWidth="2"
    strokeLinecap="round"
    strokeLinejoin="round"
  >
    <line x1="6" y1="3" x2="6" y2="15" />
    <circle cx="18" cy="6" r="3" />
    <circle cx="6" cy="18" r="3" />
    <path d="M18 9a9 9 0 0 1-9 9" />
  </svg>
);

/**
 * Блок готовых git-фраз, показывается над полем ввода (обычно при пустом чате).
 * При клике вызывает onSelect(text) — родитель вставляет текст в поле ввода.
 */
const GitPhrases = ({ onSelect }) => {
  const { t } = useTranslation('chat');
  const [activeCategory, setActiveCategory] = useState('all');

  const categories = useMemo(() => ['all', ...Array.from(new Set(GIT_PHRASES.map((p) => p.category)))], []);

  const filtered = useMemo(() => {
    if (activeCategory === 'all') return GIT_PHRASES;
    return GIT_PHRASES.filter((p) => p.category === activeCategory);
  }, [activeCategory]);

  const catLabel = (cat) => (cat === 'all' ? t('gitPhrases.categoryAll') : t(`gitPhrases.categories.${cat}`));

  return (
    <div className="git-phrases-block">
      <div className="git-phrases-block-header">
        <span className="git-phrases-block-title">
          <IconGitBranch /> {t('gitPhrases.title')}
        </span>
        <span className="git-phrases-block-hint">{t('gitPhrases.hint')}</span>
      </div>

      <div className="git-phrases-categories">
        {categories.map((cat) => (
          <button
            key={cat}
            type="button"
            className={`git-phrases-cat-btn ${activeCategory === cat ? 'git-phrases-cat-btn--active' : ''}`}
            onClick={() => setActiveCategory(cat)}
          >
            {catLabel(cat)}
          </button>
        ))}
      </div>

      <div className="git-phrases-grid">
        {filtered.map((phrase) => {
          const text = t(`gitPhrases.phrases.${phrase.id}.text`);
          const label = t(`gitPhrases.phrases.${phrase.id}.label`);
          return (
            <button
              key={phrase.id}
              type="button"
              className="git-phrases-chip"
              onClick={() => onSelect(text)}
              title={text}
            >
              <span className="git-phrases-chip-label">{label}</span>
              <span className="git-phrases-chip-category">{catLabel(phrase.category)}</span>
            </button>
          );
        })}
      </div>
    </div>
  );
};

export default GitPhrases;
