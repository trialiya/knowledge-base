import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { IconCheck } from '@/icons/index';

/**
 * Раздел «Переключиться на» в меню репозитория: локальные ветки и, когда их
 * много, поле фильтра над ними.
 *
 * Поле появляется не всегда: до десятка веток список читается глазами целиком, и
 * поле там только отнимает строку и забирает фокус. За этой границей глазами уже
 * не читается — ветки идут в алфавитном порядке, одинаковые префиксы стоят
 * рядом, и нужную ищут листанием вслепую.
 *
 * Фильтр — подстрока в любом месте имени, а не префикс: ветку помнят по хвосту
 * («тот тикет про tooltip»), а начало у половины списка общее.
 */
const FILTER_FROM = 10;

const GitBranchList = ({ branches, current, onSelect }) => {
  const { t } = useTranslation('files');
  const [filter, setFilter] = useState('');

  const query = filter.trim().toLowerCase();
  const shown = query ? branches.filter((branch) => branch.toLowerCase().includes(query)) : branches;

  // Enter имеет смысл, только когда выбирать уже не из чего: одна оставшаяся
  // ветка — это ответ, а не первый пункт списка, по которому пришлось бы гадать.
  const onKeyDown = (e) => {
    if (e.key === 'Enter' && shown.length === 1) {
      e.preventDefault();
      onSelect(shown[0]);
    }
  };

  return (
    <>
      <div className="git-menu__section">{t('git.switchTo')}</div>
      {branches.length >= FILTER_FROM && (
        <div className="git-menu__filter">
          <input
            type="text"
            className="git-menu__filter-input"
            value={filter}
            // Меню открывают ради ветки, и с длинным списком первое действие —
            // сузить его; фокус в поле экономит это движение. Клавиатура при
            // этом ничего не теряет: пункты остаются обычными кнопками, до них
            // доходит Tab, а Escape закрывает меню целиком.
            autoFocus
            placeholder={t('git.filterBranches')}
            aria-label={t('git.filterBranches')}
            onChange={(e) => setFilter(e.target.value)}
            onKeyDown={onKeyDown}
          />
        </div>
      )}
      {shown.map((branch) => {
        const isCurrent = branch === current;
        return (
          <button
            key={branch}
            type="button"
            role="menuitem"
            className="git-menu__item"
            disabled={isCurrent}
            // Обрезанные имена расходятся не только началом: `feature/…-check-4`
            // и `feature/…-check-30` читаются одинаково, и без полного имени
            // выбор ветки становится угадыванием.
            title={branch}
            onClick={() => onSelect(branch)}
          >
            <span className="git-menu__mark">{isCurrent && <IconCheck size={12} />}</span>
            <span className="git-menu__label">{branch}</span>
          </button>
        );
      })}
      {!shown.length && <div className="git-menu__empty">{t('git.noBranchMatch')}</div>}
      <div className="git-menu__sep" />
    </>
  );
};

export default GitBranchList;
