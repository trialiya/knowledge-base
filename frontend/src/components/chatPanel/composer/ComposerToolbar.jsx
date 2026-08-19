import { useTranslation } from 'react-i18next';
import DefaultNoteSelect from '../../common/DefaultNoteSelect';
import { markUnavailable } from '../../common/projectChoice';
import ModeSelector from './ModeSelector';
import { IconSend, IconStop, IconPaperclip } from '../../../icons';

/**
 * Панель под полем ввода: слева — скрепка и селекторы модели и режима, справа —
 * «отправить/остановить». Раньше кнопки жили внутри MessageInput, а модель — в
 * шапке чата; здесь всё сведено в один ряд.
 *
 * Скрепка стоит у левого края намеренно: рядом с «отправить» её задевали, и вместо
 * отправки открывался выбор файла.
 *
 * Props:
 *   model    — { config, options, selected, onChange } (может отсутствовать)
 *   mode     — { options, selected, onChange } (может отсутствовать)
 *   project  — { options, defaultId, selected, onChange } (может отсутствовать)
 *   disabled — идёт стриминг (кнопка «отправить» → «остановить», селекторы заблокированы)
 *   sendDisabled — нечего отправлять / идёт разворачивание токенов
 *   onAttach — () => void | undefined
 *   onStop   — () => void
 *   onSend   — () => void
 */
const ComposerToolbar = ({ model, mode, project, disabled, sendDisabled, onAttach, onStop, onSend }) => {
  const { t } = useTranslation('chat');

  return (
    <div className="composer-toolbar">
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

      <div className="composer-toolbar__selectors">
        {model && model.options?.length > 0 && (
          <DefaultNoteSelect
            value={model.selected}
            defaultId={model.config?.defaultModel?.id}
            options={model.options}
            onChange={model.onChange}
            disabled={disabled}
            defaultNote={t('model.default')}
            ariaLabel={t('model.aria')}
            placement="up"
          />
        )}
        {mode && mode.options?.length > 0 && (
          <ModeSelector value={mode.selected} options={mode.options} onChange={mode.onChange} disabled={disabled} />
        )}
        {/* Единственный проект показываем наравне с моделью и режимом, а не прячем, как
            это делает панель «Файлы»: там селектор — переход в другой репозиторий, и с
            одним ему некуда вести, здесь — ответ на вопрос «в каком репозитории работает
            этот чат», нужный и когда репозиторий один (кейс chat-composer-project-selector). */}
        {project && project.options?.length > 0 && (
          <DefaultNoteSelect
            value={project.selected}
            defaultId={project.defaultId}
            options={markUnavailable(project.options, t('project.unavailable'))}
            onChange={project.onChange}
            disabled={disabled}
            defaultNote={t('project.default')}
            ariaLabel={t('project.aria')}
            placement="up"
          />
        )}
        {/* Проект чата исчез из конфигурации: сказать об этом важнее, чем показать
            подменённый селектор — иначе следующий ответ придёт по другому
            репозиторию, и выглядеть это будет как обычный ответ. */}
        {project?.missing && (
          <span className="composer-toolbar__note" title={t('project.goneHint', { id: project.missing })}>
            {t('project.gone', { id: project.missing })}
          </span>
        )}
      </div>

      <div className="composer-toolbar__actions">
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
