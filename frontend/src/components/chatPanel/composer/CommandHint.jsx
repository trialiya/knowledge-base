import { useTranslation } from 'react-i18next';

/**
 * Строка над полем ввода: набранное — команда чату, а не вопрос модели.
 *
 * Это видимая часть режима команды: подсветка самого токена в поле
 * (useCommandHighlight) есть не во всех браузерах, а строка — везде. Что за
 * команда, берём по её имени: команд будет больше одной, и каждая новая
 * приносит с собой ключ `input.command.<имя>`, а не ветку здесь (за наличием
 * ключа следит i18n.test.js).
 */
const CommandHint = ({ command }) => {
  const { t } = useTranslation('chat');
  if (!command) return null;

  return (
    <div className="composer-command" role="status">
      <span className="composer-command__label">{t('input.command.label')}</span>
      <span className="composer-command__what">{t(`input.command.${command.name}`)}</span>
    </div>
  );
};

export default CommandHint;
