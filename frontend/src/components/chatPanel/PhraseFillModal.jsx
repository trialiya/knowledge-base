import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import ModalShell from '../common/ModalShell';
import PlaceholderSearchField from './PlaceholderSearchField';
import { fieldSpec } from './placeholderFields';
import { parsePlaceholders, fillPlaceholders } from './phrasePlaceholders';
import '../common/buttons.css';

/**
 * Диалог заполнения плейсхолдеров выбранной фразы.
 *
 * Монтируется под конкретную фразу и живёт до подстановки, поэтому состояние
 * полей заводится пустым и сбрасывать его на смену фразы не нужно.
 *
 * Незаполненное поле оставляет свой `{{...}}` литералом — ровно как было до
 * появления диалога: текст уедет в поле ввода, и пользователь поправит его там.
 *
 * Props:
 *   phraseText — текст фразы с плейсхолдерами
 *   onSubmit   — (filledText) => void
 *   onCancel   — закрытие без подстановки
 */
const PhraseFillModal = ({ phraseText, onSubmit, onCancel }) => {
  const { t } = useTranslation('chat');
  const fields = useMemo(() => parsePlaceholders(phraseText), [phraseText]);
  // Ключ — литерал плейсхолдера; значение зависит от вида поля: строка для
  // text, boolean для флажка, выбранный элемент для указателей.
  const [values, setValues] = useState({});

  const setValue = (raw, value) => setValues((prev) => ({ ...prev, [raw]: value }));

  const handleSubmit = (e) => {
    e.preventDefault();
    const filled = {};
    for (const { raw, type } of fields) {
      const spec = fieldSpec(type);
      const value = values[raw];
      if (spec.kind === 'boolean') filled[raw] = spec.toValue(Boolean(value));
      else if (spec.kind === 'search') {
        if (value) filled[raw] = spec.toValue(value);
      } else if (typeof value === 'string' && value.trim()) filled[raw] = value.trim();
    }
    onSubmit(fillPlaceholders(phraseText, filled));
  };

  return (
    <ModalShell onClose={onCancel} variant="sm" className="phrase-fill">
      <form onSubmit={handleSubmit}>
        <div className="phrase-fill__header">
          <h3>{t('phraseFill.title')}</h3>
          <p className="phrase-fill__hint">{t('phraseFill.hint')}</p>
        </div>

        <div className="phrase-fill__fields">
          {fields.map((field, i) => {
            const spec = fieldSpec(field.type);
            const id = `phrase-ph-${i}`;
            const value = values[field.raw];
            return (
              <div key={field.raw} className="phrase-fill__field">
                <label className="phrase-fill__label" htmlFor={id}>
                  {field.label}
                  <span className="phrase-fill__type">{t(`phraseFill.types.${field.type}`)}</span>
                </label>

                {spec.kind === 'search' && (
                  <PlaceholderSearchField
                    spec={spec}
                    inputId={id}
                    selected={value ?? null}
                    onSelect={(item) => setValue(field.raw, item)}
                    placeholder={t(`phraseFill.searchHint.${field.type}`)}
                  />
                )}

                {spec.kind === 'boolean' && (
                  <span className="phrase-fill__check">
                    <input
                      id={id}
                      type="checkbox"
                      checked={Boolean(value)}
                      onChange={(e) => setValue(field.raw, e.target.checked)}
                    />
                    <span className="phrase-fill__check-text">
                      {t(value ? 'phraseFill.booleanYes' : 'phraseFill.booleanNo')}
                    </span>
                  </span>
                )}

                {spec.kind === 'text' && (
                  <input
                    id={id}
                    className="phrase-fill__input"
                    type={spec.inputType}
                    value={typeof value === 'string' ? value : ''}
                    autoFocus={i === 0}
                    onChange={(e) => setValue(field.raw, e.target.value)}
                  />
                )}
              </div>
            );
          })}
        </div>

        <div className="modal-shell__footer">
          <button type="submit" className="btn btn--primary">
            {t('phraseFill.submit')}
          </button>
          <button type="button" className="btn btn--ghost" onClick={onCancel}>
            {t('cancel')}
          </button>
        </div>
      </form>
    </ModalShell>
  );
};

export default PhraseFillModal;
