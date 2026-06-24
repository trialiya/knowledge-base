import React, { useState, useEffect, useMemo, useCallback } from 'react';
import { useTranslation } from 'react-i18next';
import { fetchPhrases, toggleFavorite } from './phrasesApi';
import { IconStar, IconSparkleSimple } from '../../icons';
import './phrases.css';

// Сентинелы фильтров. Префиксы делают коллизию с пользовательской категорией
// практически невозможной.
const ALL = '__all__';
const FAVORITES = '__fav__';

/**
 * Блок готовых фраз над полем ввода (обычно на пустом чате).
 * Данные грузятся из GET /api/phrases. Клик по фразе → onSelect(text):
 * родитель вставляет текст в поле ввода (перезапись). Плейсхолдеры {{...}}
 * остаются литералами — пользователь правит их руками.
 *
 * reloadKey: смена значения (например, id нового чата) форсит рефетч, чтобы
 * правки из админки подхватывались при открытии чистого чата. Опционально —
 * при отсутствии достаточно рефетча на монтировании.
 */
const Phrases = ({ onSelect, reloadKey }) => {
  const { t } = useTranslation('chat');
  const [phrases, setPhrases] = useState([]);
  const [ready, setReady] = useState(false);
  const [activeCategory, setActiveCategory] = useState(ALL);

  useEffect(() => {
    let alive = true;
    setReady(false);
    fetchPhrases()
      .then((data) => alive && setPhrases(Array.isArray(data) ? data : []))
      .catch(() => alive && setPhrases([])) // при ошибке блок просто не покажется
      .finally(() => alive && setReady(true));
    return () => {
      alive = false;
    };
  }, [reloadKey]);

  const hasFavorites = useMemo(() => phrases.some((p) => p.favorite), [phrases]);

  const categories = useMemo(() => {
    const cats = Array.from(new Set(phrases.map((p) => p.category)));
    return [ALL, ...(hasFavorites ? [FAVORITES] : []), ...cats];
  }, [phrases, hasFavorites]);

  // если активная категория исчезла (сняли последнее избранное / удалили категорию) — на «Все»
  useEffect(() => {
    if (!categories.includes(activeCategory)) setActiveCategory(ALL);
  }, [categories, activeCategory]);

  const filtered = useMemo(() => {
    if (activeCategory === ALL) return phrases;
    if (activeCategory === FAVORITES) return phrases.filter((p) => p.favorite);
    return phrases.filter((p) => p.category === activeCategory);
  }, [phrases, activeCategory]);

  const onToggleFavorite = useCallback((e, phrase) => {
    e.stopPropagation(); // клик по звезде не должен вставлять текст
    const next = !phrase.favorite;
    setPhrases((prev) => prev.map((p) => (p.id === phrase.id ? { ...p, favorite: next } : p)));
    toggleFavorite(phrase.id, next).catch(() => {
      setPhrases((prev) => prev.map((p) => (p.id === phrase.id ? { ...p, favorite: !next } : p)));
    });
  }, []);

  const catLabel = (cat) => {
    if (cat === ALL) return t('phrases.categoryAll');
    if (cat === FAVORITES) return t('phrases.categoryFavorites');
    return cat; // пользовательская категория — это данные, не перевод
  };

  // пока грузится или библиотека пуста — ничего не рендерим (нет пустой обёртки с заголовком)
  if (!ready || phrases.length === 0) return null;

  return (
    <div className="phrases-block">
      <div className="phrases-block-header">
        <span className="phrases-block-title">
          <IconSparkleSimple /> {t('phrases.title')}
        </span>
        <span className="phrases-block-hint">{t('phrases.hint')}</span>
      </div>

      <div className="phrases-categories">
        {categories.map((cat) => (
          <button
            key={cat}
            type="button"
            className={`phrases-cat-btn ${activeCategory === cat ? 'phrases-cat-btn--active' : ''}`}
            onClick={() => setActiveCategory(cat)}
          >
            {catLabel(cat)}
          </button>
        ))}
      </div>

      <div className="phrases-grid">
        {filtered.map((phrase) => (
          <div key={phrase.id} className="phrases-chip-wrap">
            <button type="button" className="phrases-chip" onClick={() => onSelect(phrase.text)} title={phrase.text}>
              <span className="phrases-chip-label">{phrase.label}</span>
              <span className="phrases-chip-category">{phrase.category}</span>
            </button>
            <button
              type="button"
              className={`phrases-star ${phrase.favorite ? 'phrases-star--on' : ''}`}
              onClick={(e) => onToggleFavorite(e, phrase)}
              title={t('phrases.favoriteToggle')}
              aria-pressed={phrase.favorite}
            >
              <IconStar filled={phrase.favorite} />
            </button>
          </div>
        ))}
      </div>
    </div>
  );
};

export default Phrases;
