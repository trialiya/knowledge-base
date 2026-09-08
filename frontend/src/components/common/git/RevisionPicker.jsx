import { useMemo, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import useDismissable from '@/components/common/layout/useDismissable';
import { IconBranch, IconChevronDown, IconHistory, IconX } from '@/icons/index';
import useRevisions from './useRevisions';
import './revisionPicker.css';

/**
 * Чем панель показывает репозиторий: рабочим деревом или снимком ревизии.
 *
 * Один контрол на оба состояния, а не кнопка «войти» и отдельная кнопка
 * «выйти»: вопрос у пользователя один — «что я сейчас вижу», — и ответ на него
 * должен быть виден всегда, а не только когда режим включён. В снимке контрол
 * ещё и подписан явно, потому что перепутать его с рабочим деревом — это
 * прочитать чужую версию файла и не заметить.
 *
 * Хеши коммитов в списке не перечисляются: их вводят руками (или приходят по
 * ссылке из чата) — у истории нет полезного ответа «вот вся она».
 */
const RevisionPicker = ({ project, rev, refsToken, onChange }) => {
  const { t } = useTranslation('common');
  const [open, setOpen] = useState(false);
  const [typed, setTyped] = useState('');
  const ref = useRef(null);
  const close = useMemo(() => () => setOpen(false), []);
  useDismissable(open, ref, close);

  const refs = useRevisions({ project, refsToken, enabled: open });

  const pick = (next) => {
    setOpen(false);
    setTyped('');
    onChange(next);
  };

  const submitTyped = (event) => {
    event.preventDefault();
    const value = typed.trim();
    if (value) pick(value);
  };

  const groups = [
    { key: 'branches', items: refs.branches },
    { key: 'tags', items: refs.tags },
  ].filter((group) => group.items.length > 0);

  return (
    <div className={`rev-picker${rev ? ' rev-picker--snapshot' : ''}`} ref={ref}>
      <button
        type="button"
        className="rev-picker__trigger"
        aria-expanded={open}
        aria-haspopup="menu"
        title={rev ? t('revision.viewingHint', { rev }) : t('revision.workingTreeHint')}
        onClick={() => setOpen((was) => !was)}
      >
        {rev ? <IconHistory size={13} /> : <IconBranch size={13} />}
        <span className="rev-picker__label">{rev || t('revision.workingTree')}</span>
        <IconChevronDown size={12} />
      </button>

      {/* Выход отдельной кнопкой, а не пунктом списка: из снимка выходят чаще,
          чем переходят в соседний, и это движение в один клик. */}
      {rev && (
        <button
          type="button"
          className="icon-btn rev-picker__exit"
          title={t('revision.exit')}
          aria-label={t('revision.exit')}
          onClick={() => pick('')}
        >
          <IconX size={13} />
        </button>
      )}

      {open && (
        <div className="rev-picker__menu" role="menu">
          <form className="rev-picker__form" onSubmit={submitTyped}>
            <input
              className="rev-picker__input"
              value={typed}
              autoFocus
              placeholder={t('revision.typePlaceholder')}
              aria-label={t('revision.typeLabel')}
              onChange={(e) => setTyped(e.target.value)}
            />
          </form>

          {rev && (
            <button type="button" className="rev-picker__item" role="menuitem" onClick={() => pick('')}>
              {t('revision.workingTree')}
            </button>
          )}

          {refs.loading && <p className="rev-picker__note">{t('revision.loading')}</p>}
          {refs.error && <p className="rev-picker__note">{t('revision.loadError')}</p>}
          {!refs.loading && !refs.error && groups.length === 0 && (
            <p className="rev-picker__note">{t('revision.empty')}</p>
          )}

          {groups.map((group) => (
            <div key={group.key} className="rev-picker__group">
              <p className="rev-picker__group-title">{t(`revision.${group.key}`)}</p>
              {group.items.map((name) => (
                <button
                  key={name}
                  type="button"
                  className="rev-picker__item"
                  role="menuitem"
                  aria-current={name === rev}
                  onClick={() => pick(name)}
                >
                  {name}
                </button>
              ))}
            </div>
          ))}
        </div>
      )}
    </div>
  );
};

export default RevisionPicker;
