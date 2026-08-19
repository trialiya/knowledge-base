import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import ModalShell from '@/components/common/modal/ModalShell';
import PlaceholderSearchField from './PlaceholderSearchField';
import { fieldSpec, resolveValue } from './placeholderFields';
import { parsePlaceholders, fillPlaceholders, splitPhrase } from './phrasePlaceholders';
import '@/components/common/ui/buttons.css';

/**
 * Диалог заполнения плейсхолдеров выбранной фразы.
 *
 * Монтируется под конкретную фразу и живёт до подстановки, поэтому состояние
 * полей заводится пустым и сбрасывать его на смену фразы не нужно.
 *
 * Незаполненное поле оставляет свой `{{...}}` литералом — ровно как было до
 * появления диалога: текст уедет в поле ввода, и пользователь поправит его там.
 * У флажка пустого состояния нет: снятый — это ответ «нет», и подставляется он
 * наравне с «да». Что именно уедет в текст, видно в превью под полями.
 *
 * Props:
 *   phraseText  — текст фразы с плейсхолдерами
 *   phraseLabel — название фразы из библиотеки; оно же заголовок диалога
 *   onSubmit    — (filledText) => void
 *   onCancel    — закрытие без подстановки
 */
const PhraseFillModal = ({ phraseText, phraseLabel, project, onSubmit, onCancel }) => {
  const { t } = useTranslation('chat');
  const fields = useMemo(() => parsePlaceholders(phraseText), [phraseText]);
  const parts = useMemo(() => splitPhrase(phraseText), [phraseText]);
  // Ключ — литерал плейсхолдера; значение зависит от вида поля: строка для
  // text, boolean для флажка, выбранный элемент для указателей.
  const [values, setValues] = useState({});

  const setValue = (raw, value) => setValues((prev) => ({ ...prev, [raw]: value }));

  const handleSubmit = (e) => {
    e.preventDefault();
    const filled = {};
    for (const { raw, type } of fields) {
      const { filled: done, text } = resolveValue(type, values[raw], project);
      if (done) filled[raw] = text;
    }
    onSubmit(fillPlaceholders(phraseText, filled));
  };

  return (
    <ModalShell onClose={onCancel} variant="wide" className="phrase-fill">
      <form className="phrase-fill__form" onSubmit={handleSubmit}>
        <div className="phrase-fill__header">
          <h3>{phraseLabel || t('phraseFill.title')}</h3>
          <p className="phrase-fill__hint">{t('phraseFill.hint')}</p>
        </div>

        <div className="phrase-fill__fields">
          {fields.map((field, i) => {
            const spec = fieldSpec(field.type);
            const id = `phrase-ph-${i}`;
            const value = values[field.raw];
            // Фокус ставим на первое поле любого вида: ModalShell фокус не
            // переносит, и без этого диалог открывался бы с фокусом на body.
            const first = i === 0;
            return (
              <div key={field.raw} className="phrase-fill__field">
                <label className="phrase-fill__label" htmlFor={id}>
                  {field.label}
                  <span className="phrase-fill__type">{t(`phraseFill.types.${field.type}`)}</span>
                </label>

                {spec.kind === 'search' && (
                  <PlaceholderSearchField
                    spec={spec}
                    project={project}
                    inputId={id}
                    selected={value ?? null}
                    onSelect={(item) => setValue(field.raw, item)}
                    placeholder={t(`phraseFill.searchHint.${field.type}`)}
                    autoFocus={first}
                  />
                )}

                {spec.kind === 'boolean' && (
                  <span className="phrase-fill__check">
                    <input
                      id={id}
                      type="checkbox"
                      checked={Boolean(value)}
                      autoFocus={first}
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
                    autoFocus={first}
                    onChange={(e) => setValue(field.raw, e.target.value)}
                  />
                )}
              </div>
            );
          })}
        </div>

        {/* Сама фраза целиком: пока поле пусто, на его месте стоит подпись поля,
            дальше — то, что в него ввели. Без этого непонятно, что именно
            заполняешь и куда оно встанет. Черта над блоком делит диалог надвое:
            выше — что заполняешь, ниже — что из этого получится. */}
        <hr className="phrase-fill__sep" />

        <div className="phrase-fill__preview" aria-live="polite">
          <span className="phrase-fill__preview-title">{t('phraseFill.preview')}</span>
          <p className="phrase-fill__preview-text">
            {parts.map((part, i) => {
              if (!part.raw) return <span key={i}>{part.text}</span>;
              const { filled, preview } = resolveValue(part.type, values[part.raw], project);
              return (
                <span key={i} className={`phrase-fill__slot${filled ? ' phrase-fill__slot--filled' : ''}`}>
                  {filled ? preview : part.label}
                </span>
              );
            })}
          </p>
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
