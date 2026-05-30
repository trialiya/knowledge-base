import React from 'react';
import { IconDoc, IconFolder, IconChevronRight, IconSparkle } from './icons';

// URL вида ?view=knowledge&doc=N — её же строит useAppNavigation.buildSearch.
// Нужна как реальный href, чтобы средняя кнопка / Ctrl+Cmd-клик открывали
// документ в новой вкладке штатным механизмом браузера.
const docHref = (id) => `${window.location.pathname}?view=knowledge&doc=${encodeURIComponent(id)}`;

// Хлебные крошки строятся из parentList, который приходит с бэка вместе с
// результатом (корень → непосредственный родитель, без самого документа).
const ResultBreadcrumb = ({ parents }) => {
  if (!parents || parents.length === 0) return null;

  return (
    <div className="sr-breadcrumb">
      {parents.map((node, i) => (
        <React.Fragment key={node.id}>
          <span className="sr-breadcrumb__icon">
            <IconFolder size={11} />
          </span>
          <span className="sr-breadcrumb__name">{node.title}</span>
          {i < parents.length - 1 && (
            <span className="sr-breadcrumb__sep">
              <IconChevronRight size={9} />
            </span>
          )}
        </React.Fragment>
      ))}
    </div>
  );
};

const SearchResults = ({ query, results, onSelect }) => (
  <div className="sr-panel">
    <div className="sr-header">
      <h3 className="sr-header__title">
        Результаты поиска
        <span className="sr-header__query">«{query}»</span>
        <span className="sr-header__count">{results.length}</span>
      </h3>
    </div>

    <div className="sr-list">
      {results.length === 0 ? (
        <p className="sr-empty">Ничего не найдено</p>
      ) : (
        results.map((res) => (
          <div key={res.id} className="sr-card">
            <div className="sr-card__head">
              <span className="sr-card__icon">
                <IconDoc size={14} />
              </span>
              {/*
                Переход — только по имени. Это настоящий <a href>, поэтому:
                  • средняя кнопка мыши и Ctrl/Cmd-клик → новая вкладка (браузер сам);
                  • обычный левый клик → SPA-навигация без перезагрузки.
              */}
              <a
                className="sr-card__title"
                href={docHref(res.id)}
                onClick={(e) => {
                  // Клик с модификатором или не левой кнопкой — отдаём браузеру
                  // (открыть в новой вкладке/окне). Средняя кнопка сюда не
                  // приходит вовсе (это auxclick), её обрабатывает сам <a>.
                  if (e.metaKey || e.ctrlKey || e.shiftKey || e.altKey || e.button !== 0) return;
                  e.preventDefault();
                  onSelect(res.id);
                }}
              >
                {res.title}
              </a>
              <span className="sr-card__date">{new Date(res.updatedAt).toLocaleDateString('ru-RU')}</span>
            </div>
            <ResultBreadcrumb parents={res.parentList} />
            {res.summary && (
              <div className="sr-card__summary">
                <span className="doc-preview-tooltip__summary-label">
                  <IconSparkle size={11} />
                  AI Summary
                </span>
                <p className="doc-preview-tooltip__summary-text">{res.summary}</p>
              </div>
            )}
            {res.snippet && <p className="sr-card__snippet">{res.snippet}</p>}
          </div>
        ))
      )}
    </div>
  </div>
);

export default SearchResults;
