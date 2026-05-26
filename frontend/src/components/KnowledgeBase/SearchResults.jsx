import React from 'react';
import { IconDoc, IconFolder, IconChevronRight } from './icons';
import { findPath } from '../Utils/utils';

const ResultBreadcrumb = ({ resultId, tree }) => {
  const path = findPath(tree, resultId) || [];
  if (path.length === 0) return null;

  return (
    <div className="sr-breadcrumb">
      {path.map((node, i) => (
        <React.Fragment key={node.id}>
          <span className="sr-breadcrumb__icon">
            {node.type === 'folder' ? <IconFolder size={11} /> : <IconDoc size={11} />}
          </span>
          <span className="sr-breadcrumb__name">{node.title}</span>
          {i < path.length - 1 && (
            <span className="sr-breadcrumb__sep">
              <IconChevronRight size={9} />
            </span>
          )}
        </React.Fragment>
      ))}
    </div>
  );
};

const SearchResults = ({ query, results, tree, onSelect }) => (
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
          <div key={res.id} className="sr-card" onClick={() => onSelect(res.id)}>
            <div className="sr-card__head">
              <span className="sr-card__icon">
                <IconDoc size={14} />
              </span>
              <span className="sr-card__title">{res.title}</span>
              <span className="sr-card__date">{new Date(res.updatedAt).toLocaleDateString('ru-RU')}</span>
            </div>
            <ResultBreadcrumb resultId={res.id} tree={tree} />
            {res.snippet && <p className="sr-card__snippet">{res.snippet}</p>}
          </div>
        ))
      )}
    </div>
  </div>
);

export default SearchResults;
