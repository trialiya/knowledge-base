import { Fragment } from 'react';
import { useTranslation } from 'react-i18next';
import { IconFileText, IconDoc, IconTerminal } from '@/icons/index';
import { chatCommandBlock } from '../run/chatCommands';
import { SLASH_KIND } from './slashMenu';
import PickerDropdown from './PickerDropdown';

const ICONS = { file: <IconFileText size={13} />, doc: <IconDoc size={13} /> };

/**
 * Список со слэша: что вообще можно набрать первым символом.
 *
 * Разделов два, и это главное, что список объясняет: команда уходит чату целиком,
 * а триггер чипа лишь дописывается в сообщение. Иначе разницу приходится
 * объяснять словами.
 *
 * Props:
 *   items        — пункты (slashMenu.js)
 *   query        — набранный слэш-префикс
 *   selectedIdx  — индекс выбранной строки
 *   commandState — { running, chatStarted } для chatCommandBlock: выполнима ли команда сейчас
 *   onSelect(item) / onDismiss()
 */
const SlashMenuDropdown = ({ items, query, selectedIdx, commandState, onSelect, onDismiss }) => {
  const { t } = useTranslation('chat');

  const footer = (
    <>
      <kbd>↑↓</kbd> {t('fileInput.navigate')} · <kbd>Enter</kbd> {t('fileInput.insert')} · <kbd>Esc</kbd>{' '}
      {t('fileInput.dismiss')}
    </>
  );

  // Пункты уже сгруппированы по разделам (slashMenu.js), поэтому заголовок
  // получает первая строка каждого раздела.
  const opensSection = (item, i) => i === 0 || items[i - 1].kind !== item.kind;

  return (
    <PickerDropdown
      className="picker-dropdown--above-field"
      hint={query.length > 1 ? t('input.command.menu.hintQuery', { query }) : t('input.command.menu.hint')}
      footer={footer}
      selectedIdx={selectedIdx}
      onDismiss={onDismiss}
    >
      {items.map((item, i) => {
        const isCommand = item.kind === SLASH_KIND.COMMAND;
        // Причину отказа спрашиваем то же правило, по которому откажет отправка.
        const block = isCommand ? chatCommandBlock({ name: item.name }, commandState) : null;
        const desc = t(isCommand ? `input.command.name.${item.name}` : `input.command.insert.${item.name}`);
        return (
          <Fragment key={item.trigger}>
            {opensSection(item, i) && (
              <div className="picker-section">
                {t(isCommand ? 'input.command.menu.commands' : 'input.command.menu.inserts')}
              </div>
            )}
            <div
              className={`picker-item${i === selectedIdx ? ' picker-item--selected' : ''}${
                block ? ' picker-item--blocked' : ''
              }`}
              onMouseDown={(e) => {
                e.preventDefault();
                onSelect(item);
              }}
            >
              <span className="picker-item__icon">{isCommand ? <IconTerminal size={13} /> : ICONS[item.name]}</span>
              <span className="picker-item__body">
                <span className="picker-item__name">
                  <span className="picker-item__trigger">{item.trigger}</span>
                  {item.args && <span className="picker-item__args"> {t(`input.command.args.${item.name}`)}</span>}
                </span>
                <span className="picker-item__desc" title={desc}>
                  {desc}
                </span>
              </span>
              {block ? (
                <span className="picker-item__reason">{t(`input.command.blocked.${block}`)}</span>
              ) : (
                item.alt.length > 0 && <span className="picker-item__alt">{item.alt.join(' ')}</span>
              )}
            </div>
          </Fragment>
        );
      })}
    </PickerDropdown>
  );
};

export default SlashMenuDropdown;
