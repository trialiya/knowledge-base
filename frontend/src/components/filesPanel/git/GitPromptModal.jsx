import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import ModalShell from '@/components/common/modal/ModalShell';
import '@/components/common/ui/buttons.css';
import './gitPromptModal.css';

/**
 * Одна строка ввода для команды, которой нужен текст: имя новой ветки или
 * сообщение коммита.
 *
 * Одна модалка на оба случая, потому что различаются они только подписями: поле,
 * Enter — подтвердить, пустое значение — кнопка выключена. Отдельная модалка на
 * каждую команду означала бы два файла с одинаковым телом и разными строками.
 *
 * `hint` — то, чего пользователь не видит сам: сколько файлов уйдёт в коммит,
 * от какой ветки отпочкуется новая.
 */
const GitPromptModal = ({ open, title, label, hint, placeholder, confirmLabel, multiline, onConfirm, onCancel }) => {
  const { t } = useTranslation('files');
  const [value, setValue] = useState('');

  if (!open) return null;

  const submit = () => {
    const text = value.trim();
    if (!text) return;
    setValue('');
    onConfirm(text);
  };

  const cancel = () => {
    setValue('');
    onCancel();
  };

  return (
    <ModalShell open onClose={cancel} variant="sm" className="git-prompt">
      <h3 className="git-prompt__title">{title}</h3>
      {hint && <p className="git-prompt__hint">{hint}</p>}
      <label className="git-prompt__label">
        {label}
        {multiline ? (
          <textarea
            className="git-prompt__input git-prompt__input--area"
            value={value}
            placeholder={placeholder}
            rows={3}
            autoFocus
            onChange={(e) => setValue(e.target.value)}
            // Enter в многострочном поле переводит строку — подтверждает Ctrl/⌘+Enter,
            // как в композере чата.
            onKeyDown={(e) => {
              if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) submit();
            }}
          />
        ) : (
          <input
            className="git-prompt__input"
            value={value}
            placeholder={placeholder}
            autoFocus
            onChange={(e) => setValue(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter') submit();
            }}
          />
        )}
      </label>
      <div className="modal-shell__footer">
        <button type="button" className="btn btn--primary" disabled={!value.trim()} onClick={submit}>
          {confirmLabel}
        </button>
        <button type="button" className="btn btn--ghost" onClick={cancel}>
          {t('git.cancel')}
        </button>
      </div>
    </ModalShell>
  );
};

export default GitPromptModal;
