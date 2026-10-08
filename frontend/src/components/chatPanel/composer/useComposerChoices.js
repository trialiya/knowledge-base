import { useCallback, useMemo } from 'react';
import { modelLabelOf } from '../run/useModelConfig';
import { resolveProjectChoice } from '@/components/common/config/projectChoice';
import { effectiveReasoning, reasoningLevelName, reasoningOf } from '../run/reasoningChoice';
import { stampChipProject } from './fileChips';

/**
 * Что выбрано в композере активного чата — модель, режим, уровень рассуждений, проект —
 * и что из этого показать: пропсы селекторов под полем ввода и подписи для вкладки
 * «Инфо». Выбор хранится у чата id-шниками, а показывать надо то, на чём прогон
 * действительно пойдёт, — с откатом к дефолту для всего, что из конфигурации исчезло.
 * Те же правила при отправке применяет useChatRun, и расходиться им нельзя: на экране
 * один выбор, и прогон обязан уехать с ним же.
 *
 * @param {object} p
 * @param {Function} p.t i18n-функция с namespace `chat` первым
 * @param {object} p.drafts { getDraftFor, handleTextChange, flushDrafts, bumpDraftSignal } —
 *   смене проекта нужно переписать чипы в черновике (см. handleProjectChange)
 */
export default function useComposerChoices({
  t,
  activeChat,
  activeChatId,
  modelConfig,
  modelOptions,
  modeOptions,
  projectOptions,
  defaultProjectId,
  changeModel,
  changeMode,
  changeReasoning,
  changeProject,
  drafts,
}) {
  // Выбранная в селекторе модель. Если у чата модель не задана или её больше нет
  // в конфиге — показываем дефолтную (чтобы select оставался валидным).
  const selectedModelId = useMemo(() => {
    const def = modelConfig?.defaultModel?.id || '';
    const m = activeChat?.model;
    return m && modelOptions.some((o) => o.id === m) ? m : def;
  }, [activeChat, modelOptions, modelConfig]);

  // Выбранный режим чата. Нет режима / режим убран из конфига → «без режима» ('').
  const selectedModeId = useMemo(() => {
    const m = activeChat?.mode;
    return m && modeOptions.some((o) => o.id === m) ? m : '';
  }, [activeChat, modeOptions]);

  // Уровни выбранной модели (null — селектора нет) и уровень, на котором пойдёт прогон.
  const reasoningLevels = useMemo(() => reasoningOf(modelConfig, selectedModelId), [modelConfig, selectedModelId]);
  const selectedReasoning = effectiveReasoning(reasoningLevels, activeChat?.reasoning ?? null);

  // Проект, выбранный в селекторе: у чата → дефолтный. Отдельно — id, который у чата
  // записан, но которого в конфиге больше нет: селектор показывает дефолт, а рядом
  // должно стоять предупреждение, иначе подмена репозитория пройдёт незамеченной.
  const { selected: selectedProjectId, missing: missingProjectId } = resolveProjectChoice(
    activeChat?.project ?? null,
    projectOptions,
    defaultProjectId,
  );
  // Для адресов — только не-дефолтный проект: дефолтный в схеме не пишется, а
  // пустое значение и означает его (см. urlScheme.filesUrl).
  const projectInLinks = selectedProjectId && selectedProjectId !== defaultProjectId ? selectedProjectId : null;

  const handleModelChange = useCallback((newId) => changeModel(activeChatId, newId), [activeChatId, changeModel]);
  const handleModeChange = useCallback((newId) => changeMode(activeChatId, newId), [activeChatId, changeMode]);
  const handleReasoningChange = useCallback(
    (newId) => changeReasoning(activeChatId, newId),
    [activeChatId, changeReasoning],
  );
  // Чип в черновике мог остаться без имени проекта: так писала прежняя версия
  // формата, и означает это «репозиторий чата». Пока чат ещё работает в прежнем,
  // вписываем его в такие чипы — после смены проекта тот же путь вёл бы уже в
  // другой файл, и подставился бы он молча, в отправленном сообщении.
  //
  // Сравниваем с РАЗРЕШЁННЫМ проектом, а не с записанным у чата: выбрать дефолт в
  // чате, чей проект исчез из конфигурации, — способ убрать предупреждение, и
  // репозиторий при этом не меняется.
  const { getDraftFor, handleTextChange, flushDrafts, bumpDraftSignal } = drafts;
  const handleProjectChange = useCallback(
    (newId) => {
      if (newId !== selectedProjectId) {
        const draft = getDraftFor(activeChatId);
        const stamped = stampChipProject(draft, selectedProjectId);
        if (stamped !== draft) {
          handleTextChange(activeChatId, stamped);
          // Пишем на диск сразу, не дожидаясь отложенной записи: проект у чата
          // меняется немедленно, и вкладка, погибшая в эти полсекунды, оставила бы
          // в хранилище чипы без проекта — то есть уже про новый репозиторий.
          flushDrafts();
          bumpDraftSignal();
        }
      }
      changeProject(activeChatId, newId);
    },
    [activeChatId, changeProject, flushDrafts, getDraftFor, handleTextChange, bumpDraftSignal, selectedProjectId],
  );

  // Подписи для вкладки «Инфо»: в чате хранятся id, а показывать осмысленно
  // человекочитаемый label из конфига.
  const modelLabel = useMemo(() => modelLabelOf(modelOptions, selectedModelId), [modelOptions, selectedModelId]);
  const modeLabel = useMemo(
    () => modeOptions.find((o) => o.id === selectedModeId)?.label || null,
    [modeOptions, selectedModeId],
  );
  // У модели без уровней строки нет: «по умолчанию» там означало бы выбор, которого не было.
  const reasoningLabel = useMemo(
    () => (reasoningLevels ? reasoningLevelName(t, reasoningLevels, selectedReasoning) : null),
    [t, reasoningLevels, selectedReasoning],
  );
  // Исчезнувший проект показываем самим id и говорим, что его больше нет: подписи
  // для него уже нет, а «пусто» читалось бы как «проект не выбран».
  const projectLabel = useMemo(
    () =>
      missingProjectId
        ? t('project.goneValue', { id: missingProjectId })
        : projectOptions.find((o) => o.id === selectedProjectId)?.label || null,
    [missingProjectId, projectOptions, selectedProjectId, t],
  );

  return {
    selectedProjectId,
    model: { config: modelConfig, options: modelOptions, selected: selectedModelId, onChange: handleModelChange },
    mode: { options: modeOptions, selected: selectedModeId, onChange: handleModeChange },
    reasoning: { levels: reasoningLevels, selected: selectedReasoning, onChange: handleReasoningChange },
    project: {
      options: projectOptions,
      defaultId: defaultProjectId,
      selected: selectedProjectId,
      inLinks: projectInLinks,
      missing: missingProjectId,
      onChange: handleProjectChange,
    },
    labels: { modelLabel, modeLabel, reasoningLabel, projectLabel },
  };
}
