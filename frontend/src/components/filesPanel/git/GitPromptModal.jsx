import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import ModalShell from '@/components/common/modal/ModalShell';
import '@/components/common/ui/buttons.css';
import './gitPromptModal.css';

/**
 * Одна строка ввода для команды, которой нужен текст: имя новой ветки.
 *
 * Подписи приходят снаружи: команд, которым хватает одной строки, может стать
 * больше, а различались бы такие модалки только текстом. Коммит к ним не
 * относится — там выбирают ещё и файлы, и у него своё окно (см. common/git).
 *
 * `hint` — то, чего пользователь не видит сам: от какой ветки отпочкуется новая.
 */
const GitPromptModal = ({ open, title, label, hint, placeholder, confirmLabel, onConfirm, onCancel }) => {
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
