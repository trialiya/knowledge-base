import { useTranslation } from 'react-i18next';
import DefaultNoteSelect from '@/components/common/config/DefaultNoteSelect';
import { markUnavailable } from '@/components/common/config/projectChoice';
import ModeSelector from './ModeSelector';
import { IconSend, IconStop, IconPaperclip } from '@/icons/index';

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
 *   busy     — писать некуда: идёт сжатие контекста или прогон ещё не назвал свой runId
 *   generating — идёт ответ модели: появляется «остановить», селекторы заблокированы.
 *               «Отправить» при этом остаётся живой — сообщение встаёт в очередь прогона,
 *               и обе кнопки стоят рядом, а не подменяют друг друга
 *   stoppable — занятость прерываема: сжатие контекста (/compact) прервать нельзя, и
 *               кнопка «остановить» на нём неактивна
 *   sendDisabled — нечего отправлять / идёт разворачивание токенов
 *   onAttach — () => void | undefined
 *   onStop   — () => void
 *   onSend   — () => void
 */
const ComposerToolbar = ({
  model,
  mode,
  project,
  busy,
  generating = false,
  stoppable = true,
  sendDisabled,
  onAttach,
  onStop,
  onSend,
}) => {
  const { t } = useTranslation('chat');
  // Выбор модели, режима и проекта запирается на всё время занятости чата: прогон уже
  // едет на своих настройках, а сообщение из очереди поедет на тех, что стояли в момент
  // отправки (см. PendingMessageService.PendingOptions). Разрешить переключение здесь
  // значило бы обещать смену, которой не будет.
  const selectorsLocked = busy || generating;

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
            disabled={selectorsLocked}
            defaultNote={t('model.default')}
            ariaLabel={t('model.aria')}
            placement="up"
          />
        )}
        {mode && mode.options?.length > 0 && (
          <ModeSelector
            value={mode.selected}
            options={mode.options}
            onChange={mode.onChange}
            disabled={selectorsLocked}
          />
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
            disabled={selectorsLocked}
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
        {generating && (
          <button
            type="button"
            className="message-action-btn message-action-btn--stop"
            onClick={onStop}
            disabled={!stoppable}
            title={t('input.stop')}
          >
            <IconStop />
          </button>
        )}
        <button
          type="button"
          className="message-action-btn message-action-btn--send"
          onClick={onSend}
          disabled={busy || sendDisabled}
          title={generating && !busy ? t('input.sendDuringRun') : t('input.send')}
        >
          <IconSend />
        </button>
      </div>
    </div>
  );
};

export default ComposerToolbar;
