import React, { useState, useMemo } from 'react';
import './GitPhrases.css';

// ─── Список готовых фраз ───────────────────────────────────────────────────
export const GIT_PHRASES = [
  // ── Анализ ─────────────────────────────────────────────────────────────
  {
    category: 'Анализ',
    label: 'История коммитов',
    text: 'Покажи историю коммитов и объясни ключевые изменения для файла {{файл}}',
  },
  // {
  //   category: 'Анализ',
  //   label: 'Разница между ветками',
  //   text: 'Покажи diff между ветками {{ветка_1}} и {{ветка_2}} и объясни суть изменений.',
  // },
  {
    category: 'Анализ',
    label: 'Поиск коммита по тексту',
    text: 'Найди коммит, в котором была добавлена или удалена строка содержащая «{{текст}}».',
  },
  {
    category: 'Анализ',
    label: 'Просмотри изменений',
    text: 'Найди коммит, в котором была добавлена или удалена строка содержащая «{{текст}}».',
  },

  // ── Коммиты ──────────────────────────────────────────────────────────────
  {
    category: 'Коммиты',
    label: 'Имя коммита по незакоммиченным',
    text: 'Посмотри что в незакоммиченных изменениях. Опиши изменения и подготовь имя коммита по аналогии на английском — концентрируйся на ценности, а не на технических изменениях в коде.',
  },

  // ── Теги / Релизы ─────────────────────────────────────────────────────────
  // {
  //   category: 'Теги / Релизы',
  //   label: 'Changelog между тегами',
  //   text: 'Сгенерируй changelog между тегами {{тег_1}} и {{тег_2}} в формате Markdown.',
  // },

  // ── Remote / GitHub ──────────────────────────────────────────────────────
  // {
  //   category: 'Remote / GitHub',
  //   label: 'Описание Pull Request',
  //   text: 'Напиши описание Pull Request для ветки {{ветка}}. Изменения: {{описание_изменений}}',
  // },
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

const ALL_CATEGORIES = ['Все', ...Array.from(new Set(GIT_PHRASES.map((p) => p.category)))];

/**
 * Блок готовых git-фраз, показывается над полем ввода (обычно при пустом чате).
 * При клике вызывает onSelect(text) — родитель вставляет текст в поле ввода.
 */
const GitPhrases = ({ onSelect }) => {
  const [activeCategory, setActiveCategory] = useState('Все');

  const filtered = useMemo(() => {
    if (activeCategory === 'Все') return GIT_PHRASES;
    return GIT_PHRASES.filter((p) => p.category === activeCategory);
  }, [activeCategory]);

  return (
    <div className="git-phrases-block">
      <div className="git-phrases-block-header">
        <span className="git-phrases-block-title">
          <IconGitBranch /> Готовые фразы для работы с Git
        </span>
        <span className="git-phrases-block-hint">Нажмите, чтобы вставить в поле ввода</span>
      </div>

      <div className="git-phrases-categories">
        {ALL_CATEGORIES.map((cat) => (
          <button
            key={cat}
            type="button"
            className={`git-phrases-cat-btn ${activeCategory === cat ? 'git-phrases-cat-btn--active' : ''}`}
            onClick={() => setActiveCategory(cat)}
          >
            {cat}
          </button>
        ))}
      </div>

      <div className="git-phrases-grid">
        {filtered.map((phrase, i) => (
          <button
            key={i}
            type="button"
            className="git-phrases-chip"
            onClick={() => onSelect(phrase.text)}
            title={phrase.text}
          >
            <span className="git-phrases-chip-label">{phrase.label}</span>
            <span className="git-phrases-chip-category">{phrase.category}</span>
          </button>
        ))}
      </div>
    </div>
  );
};

export default GitPhrases;
