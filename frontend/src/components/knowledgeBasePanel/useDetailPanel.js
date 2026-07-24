import { useState, useEffect, useRef } from 'react';

/**
 * Состояние детали узла базы знаний, общее для ЦЕНТРА (редактор содержимого) и
 * ПРАВОЙ панели (описание, вложения):
 *   - fullscreen: 'about' | 'content' | null (что раскрыто на весь экран)
 *   - showHistory: открыта ли модалка истории
 *   - contentDraft: «поднятый» черновик описания, чтобы встроенный редактор и
 *     полноэкранный («развернуть») делили один источник правды.
 *
 * Хук живёт в KnowledgeBase, то есть переживает смену выбранного узла (раньше он
 * сидел внутри DocumentDetail с `key={node.id}` и просто пересоздавался).
 * Поэтому смену узла он обрабатывает сам — иначе черновик одного документа
 * протекал бы в другой и редактор предлагал сохранить чужой текст.
 *
 * @param savedContent — сохранённое описание узла (node.description)
 * @param nodeId       — id узла; его смена сбрасывает состояние детали
 */
export default function useDetailPanel(savedContent = '', nodeId = null) {
  const [fullscreen, setFullscreen] = useState(null);
  const [showHistory, setShowHistory] = useState(false);
  const [contentDraft, setContentDraft] = useState(savedContent);

  const savedRef = useRef(savedContent);
  const nodeRef = useRef(nodeId);

  useEffect(() => {
    if (nodeId !== nodeRef.current) {
      // Открыт другой узел — начинаем с чистого листа: черновик, развёрнутый
      // редактор и история относились к предыдущему документу.
      nodeRef.current = nodeId;
      savedRef.current = savedContent;
      setContentDraft(savedContent);
      setFullscreen(null);
      setShowHistory(false);
      return;
    }
    // Тот же узел, но сохранённое описание изменилось извне (сохранение,
    // восстановление из истории, догрузка полного документа поверх краткого
    // стаба из дерева) — подхватываем его в черновик, но только если у
    // пользователя нет несохранённых правок (черновик == прежнее сохранённое).
    if (savedContent === savedRef.current) return;
    setContentDraft((prev) => (prev === savedRef.current ? savedContent : prev));
    savedRef.current = savedContent;
  }, [nodeId, savedContent]);

  return {
    fullscreen,
    setFullscreen,
    showHistory,
    setShowHistory,
    contentDraft,
    setContentDraft,
  };
}
