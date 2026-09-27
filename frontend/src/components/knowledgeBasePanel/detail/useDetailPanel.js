import { useState } from 'react';

/**
 * Состояние детали узла базы знаний, общее для ЦЕНТРА (редактор содержимого) и
 * ПРАВОЙ панели (summary, вложения):
 *   - fullscreen: раскрыт ли редактор содержимого на весь экран
 *   - showHistory: открыта ли модалка истории
 *   - contentDraft: «поднятый» черновик описания, чтобы встроенный редактор и
 *     полноэкранный («развернуть») делили один источник правды.
 *
 * Хук живёт в KnowledgeBase, то есть переживает смену выбранного узла, поэтому
 * смену узла он обрабатывает сам — иначе черновик одного документа
 * протекал бы в другой и редактор предлагал сохранить чужой текст.
 *
 * @param savedContent — сохранённое описание узла (node.description)
 * @param nodeId       — id узла; его смена сбрасывает состояние детали
 */
export default function useDetailPanel(savedContent = '', nodeId = null) {
  const [fullscreen, setFullscreen] = useState(false);
  const [showHistory, setShowHistory] = useState(false);
  const [contentDraft, setContentDraft] = useState(savedContent);

  // Состояние следует за пропами в рендере, а не эффектом: апдейтер черновика,
  // запущенный из эффекта, выполнялся бы позже — и сравнивал бы черновик уже с
  // НОВЫМ сохранённым описанием, так что откат из истории оставался бы в
  // редакторе несохранённой «правкой» со старым текстом.
  const [prev, setPrev] = useState({ nodeId, savedContent });
  if (prev.nodeId !== nodeId) {
    // Открыт другой узел — начинаем с чистого листа: черновик, развёрнутый
    // редактор и история относились к предыдущему документу.
    setPrev({ nodeId, savedContent });
    setContentDraft(savedContent);
    setFullscreen(false);
    setShowHistory(false);
  } else if (prev.savedContent !== savedContent) {
    // Тот же узел, но сохранённое описание изменилось извне (сохранение,
    // восстановление из истории, догрузка полного документа поверх краткого
    // стаба из дерева) — подхватываем его в черновик, но только если у
    // пользователя нет несохранённых правок (черновик == прежнее сохранённое).
    setPrev({ nodeId, savedContent });
    if (contentDraft === prev.savedContent) setContentDraft(savedContent);
  }

  return {
    fullscreen,
    setFullscreen,
    showHistory,
    setShowHistory,
    contentDraft,
    setContentDraft,
  };
}
