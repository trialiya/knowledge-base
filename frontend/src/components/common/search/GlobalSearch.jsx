import { useTranslation } from 'react-i18next';
import { IconSearch } from '@/icons/index';
import './globalSearch.css';

/**
 * Глобальная строка поиска в шапке вкладок: лупа, поле и подсказка про Enter.
 *
 * Видна во всех разделах и всегда уводит в раздел «Поиск» — искать одинаково
 * нужно и из чата, и из базы знаний, и из файлов. Чем именно уточнять поиск,
 * решает уже он сам: набор фильтров у каждой категории свой, и в одну строку
 * шапки они не помещаются.
 *
 * props:
 *   value    — текст запроса (controlled)
 *   onChange — (text) => void
 *   onSubmit — () => void (Enter в поле)
 */
const GlobalSearch = ({ value, onChange, onSubmit }) => {
  const { t } = useTranslation();

  return (
    <div className="global-search">
      <span className="global-search__icon">
        <IconSearch size={15} />
      </span>
      <input
        type="text"
        placeholder={t('search.placeholder')}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === 'Enter') onSubmit();
        }}
      />
      <span className="global-search__hint" aria-hidden="true">
        ↵ Enter
      </span>
    </div>
  );
};

export default GlobalSearch;
