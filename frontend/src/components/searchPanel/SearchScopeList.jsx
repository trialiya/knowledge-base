import { useTranslation } from 'react-i18next';
import { IconFileText, IconDoc, IconMessage } from '@/icons/index';
import { SEARCH_SCOPES } from '@/constants/searchScope';
import useListNavigation from '@/components/common/search/useListNavigation';

const ICONS = { files: IconFileText, docs: IconDoc, chats: IconMessage };

/**
 * Категории поиска в левой панели: файлы · документы · чаты.
 *
 * Выбрана всегда ровно одна — это переключатель, а не набор галочек, — поэтому
 * место чекбокса занимает число найденного. Оно и есть ответ на вопрос «стоит
 * ли сюда переключаться». Пока категория не ответила (или запроса ещё нет),
 * счётчика нет вовсе: пустая плашка врала бы про ноль найденного.
 */
const SearchScopeList = ({ scope, counts, onSelect }) => {
  const { t } = useTranslation('search');
  const onKeyDown = useListNavigation();

  return (
    <ul className="ws-list" role="listbox" aria-label={t('scopes')} tabIndex={0} onKeyDown={onKeyDown}>
      {SEARCH_SCOPES.map((key) => {
        const Icon = ICONS[key];
        const active = key === scope;
        const count = counts[key];
        return (
          <li
            key={key}
            role="option"
            aria-selected={active}
            data-ws-item
            tabIndex={-1}
            className={`ws-item${active ? ' ws-item--active' : ''}`}
            onClick={() => onSelect(key)}
          >
            <span className="ws-item__icon">
              <Icon size={14} />
            </span>
            <span className="ws-item__label">{t(`scope.${key}`)}</span>
            {count != null && <span className="search-scopes__count">{count}</span>}
          </li>
        );
      })}
    </ul>
  );
};

export default SearchScopeList;
