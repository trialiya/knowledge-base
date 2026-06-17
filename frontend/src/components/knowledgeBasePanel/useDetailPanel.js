import { useState, useEffect, useRef } from 'react';

/**
 * Local UI state shared by FolderDetail and DocumentDetail:
 *   - attachmentCount: badge count reported by AttachmentPanel
 *   - fullscreen: 'about' | 'content' | null (which pane is expanded)
 *   - showHistory: history modal open?
 *   - contentDraft: «поднятый» черновик описания, чтобы встроенный редактор и
 *     полноэкранный («развернуть») делили один источник правды.
 *
 * @param savedContent — текущее сохранённое описание узла (node.description).
 */
export default function useDetailPanel(savedContent = '') {
  const [attachmentCount, setAttachmentCount] = useState(0);
  const [fullscreen, setFullscreen] = useState(null);
  const [showHistory, setShowHistory] = useState(false);
  const [contentDraft, setContentDraft] = useState(savedContent);

  // Когда сохранённое описание меняется извне (сохранение, восстановление из
  // истории, рефетч), подхватываем его в черновик — но только если у
  // пользователя нет несохранённых правок (черновик == прежнее сохранённое).
  // Так внешнее обновление не затирает правки в процессе редактирования.
  const savedRef = useRef(savedContent);
  useEffect(() => {
    if (savedContent === savedRef.current) return;
    setContentDraft((prev) => (prev === savedRef.current ? savedContent : prev));
    savedRef.current = savedContent;
  }, [savedContent]);

  return {
    attachmentCount,
    setAttachmentCount,
    fullscreen,
    setFullscreen,
    showHistory,
    setShowHistory,
    contentDraft,
    setContentDraft,
  };
}
