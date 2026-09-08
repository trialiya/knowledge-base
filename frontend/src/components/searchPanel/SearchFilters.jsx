import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { SEARCH_SCOPE } from '@/constants/searchScope';
import { SEARCH_MODES } from '@/constants/searchMode';
import ListboxSelect from '@/components/common/ui/ListboxSelect';
import RevisionPicker from '@/components/common/git/RevisionPicker';

/**
 * Текстовый фильтр с отложенным применением.
 *
 * Черновик локальный, а в адрес значение уходит по Enter или уходу фокуса:
 * каждая нажатая буква — это три запроса на бэкенд (счётчики считают все
 * категории), и печатать `backend/**` под непрерывный перезапрос поиска нельзя.
 * Значение приходит и снаружи (кнопка «Назад», открытая ссылка) — черновик
 * подстраивается в рендере, эффект дал бы лишний проход.
 */
const FilterField = ({ id, label, hint, value, onCommit }) => {
  const [draft, setDraft] = useState(value);
  const [prev, setPrev] = useState(value);
  if (prev !== value) {
    setPrev(value);
    setDraft(value);
  }

  return (
    <label className="search-filters__field" htmlFor={id}>
      <span className="search-filters__label">{label}</span>
      <input
        id={id}
        type="text"
        className="search-filters__input"
        value={draft}
        placeholder={hint}
        onChange={(e) => setDraft(e.target.value)}
        onBlur={() => onCommit(draft)}
        onKeyDown={(e) => {
          if (e.key === 'Enter') onCommit(draft);
          if (e.key === 'Escape') setDraft(value);
        }}
      />
    </label>
  );
};

/**
 * Уточнения выбранной категории — и только её: у файлов свои (маска пути,
 * ревизия, регулярка, неотслеживаемые), у документов свой режим поиска, у чатов
 * уточнять нечего. Общего набора фильтров тут быть не может: категории ищут
 * разными эндпоинтами с разными параметрами.
 */
const SearchFilters = ({
  scope,
  path,
  project,
  projectOptions,
  onProjectChange,
  rev,
  regex,
  untracked,
  mode,
  onRefine,
}) => {
  const { t } = useTranslation('search');

  if (scope === SEARCH_SCOPE.FILES) {
    return (
      <div className="search-filters">
        {projectOptions.length > 1 && (
          <div className="search-filters__field">
            <span className="search-filters__label">{t('filters.project')}</span>
            <ListboxSelect
              value={project}
              options={projectOptions}
              onChange={onProjectChange}
              ariaLabel={t('filters.project')}
            />
          </div>
        )}
        {/* Тот же контрол, что и в «Файлах»: ревизию выбирают из веток и тегов, а
            печатают только хеш — его в списке нет и быть не может. */}
        <div className="search-filters__field">
          <span className="search-filters__label">{t('filters.rev')}</span>
          <RevisionPicker project={project} rev={rev} onChange={(next) => onRefine({ searchRev: next })} />
        </div>
        <FilterField
          id="search-filter-path"
          label={t('filters.path')}
          hint={t('filters.pathHint')}
          value={path}
          onCommit={(v) => onRefine({ searchPath: v.trim() })}
        />
        <label className="search-filters__check">
          <input type="checkbox" checked={regex} onChange={(e) => onRefine({ searchRegex: e.target.checked })} />
          <span>{t('filters.regex')}</span>
        </label>
        {/* В снимке коммита неотслеживаемых файлов нет — их не было бы и в снимке.
            Галочка при заданной ревизии выключена и снята, но из адреса не
            стирается: сняли ревизию — вернулись к тому, что искали в рабочем
            дереве. */}
        <label className={`search-filters__check${rev ? ' search-filters__check--off' : ''}`}>
          <input
            type="checkbox"
            checked={untracked && !rev}
            disabled={!!rev}
            onChange={(e) => onRefine({ searchUntracked: e.target.checked })}
          />
          <span>{t('filters.untracked')}</span>
        </label>
        {rev && <p className="search-filters__hint">{t('filters.untrackedInRev')}</p>}
      </div>
    );
  }

  if (scope === SEARCH_SCOPE.DOCS) {
    return (
      <div className="search-filters">
        <span className="search-filters__label">{t('filters.mode')}</span>
        <div className="search-filters__modes" role="radiogroup" aria-label={t('filters.mode')}>
          {SEARCH_MODES.map((m) => (
            <button
              key={m}
              type="button"
              role="radio"
              aria-checked={mode === m}
              className={`btn btn--sm${mode === m ? ' btn--primary' : ' btn--ghost'}`}
              onClick={() => onRefine({ mode: m })}
            >
              {t(`common:search.${m}`)}
            </button>
          ))}
        </div>
      </div>
    );
  }

  return <p className="ws-hint">{t('filters.noneForChats')}</p>;
};

export default SearchFilters;
