import React from 'react';
import { useTranslation } from 'react-i18next';
import ModelSelector from './ModelSelector';
import ModeSelector from './ModeSelector';
import { IconSend, IconStop, IconPaperclip } from '../../icons';

/**
 * Панель под полем ввода: слева — селекторы модели и режима, справа — кнопки
 * «прикрепить файл» и «отправить/остановить». Раньше кнопки жили внутри
 * MessageInput, а модель — в шапке чата; здесь всё сведено в один ряд.
 *
 * Props:
 *   model    — { config, options, selected, onChange } (может отсутствовать)
 *   mode     — { options, selected, onChange } (может отсутствовать)
 *   disabled — идёт стриминг (кнопка «отправить» → «остановить», селекторы заблокированы)
 *   sendDisabled — нечего отправлять / идёт разворачивание токенов
 *   onAttach — () => void | undefined
 *   onStop   — () => void
 *   onSend   — () => void
 */
const ComposerToolbar = ({ model, mode, disabled, sendDisabled, onAttach, onStop, onSend }) => {
  const { t } = useTranslation('chat');

  return (
    <div className="composer-toolbar">
      <div className="composer-toolbar__selectors">
        {model && model.options?.length > 0 && (
          <ModelSelector
            value={model.selected}
            defaultId={model.config?.defaultModel?.id}
            options={model.options}
            onChange={model.onChange}
            disabled={disabled}
          />
        )}
        {mode && mode.options?.length > 0 && (
          <ModeSelector value={mode.selected} options={mode.options} onChange={mode.onChange} disabled={disabled} />
        )}
      </div>

      <div className="composer-toolbar__actions">
        {onAttach && (
          <button
            type="button"
            className="icon-btn composer-toolbar__attach"
            onClick={onAttach}
            title={t('input.attach')}
            tabIndex={-1}
          >
            <IconPaperclip />
          </button>
        )}
        <button
          type="button"
          className={
            disabled ? 'message-action-btn message-action-btn--stop' : 'message-action-btn message-action-btn--send'
          }
          onClick={disabled ? onStop : onSend}
          disabled={!disabled && sendDisabled}
          title={disabled ? t('input.stop') : t('input.send')}
        >
          {disabled ? <IconStop /> : <IconSend />}
        </button>
      </div>
    </div>
  );
};

export default ComposerToolbar;
