import { useTranslation } from 'react-i18next';

/**
 * Строка над полем ввода: набранное — команда чату, а не вопрос модели, — и, если
 * выполнить её сейчас нельзя, почему.
 *
 * Это видимая часть режима команды: подсветка самого токена в поле
 * (useCommandHighlight) есть не во всех браузерах, а строка — везде. Поэтому же
 * пустой живой регион сидит в разметке всегда: скринридер объявляет изменение
 * ТЕКСТА внутри уже существующего `role="status"`, а регион, появившийся вместе
 * со своим текстом, чаще всего не объявляется вовсе — и единственная замена
 * подсветке молчала бы.
 *
 * Что за команда и что ей мешает, берём по именам: команд будет больше одной, и
 * каждая новая приносит с собой ключ `input.command.name.<имя>`, а не ветку здесь
 * (за наличием ключа следит i18n.test.js).
 */
const CommandHint = ({ command, block }) => {
  const { t } = useTranslation('chat');

  return (
    <div className="composer-command" role="status">
      {command && (
        <span className={`composer-command__row${block ? ' composer-command__row--blocked' : ''}`}>
          <span className="composer-command__label">{t('input.command.label')}</span>
          <span className="composer-command__name">{t(`input.command.name.${command.name}`)}</span>
          <span className="composer-command__note">
            — {block ? t(`input.command.blocked.${block}`) : t('input.command.toChat')}
          </span>
        </span>
      )}
    </div>
  );
};

export default CommandHint;
