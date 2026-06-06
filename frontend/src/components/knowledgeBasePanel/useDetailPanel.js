import { useState } from 'react';

/**
 * Local UI state shared by FolderDetail and DocumentDetail:
 *   - attachmentCount: badge count reported by AttachmentPanel
 *   - fullscreen: 'about' | 'content' | null (which pane is expanded)
 *   - showHistory: history modal open?
 */
export default function useDetailPanel() {
  const [attachmentCount, setAttachmentCount] = useState(0);
  const [fullscreen, setFullscreen] = useState(null);
  const [showHistory, setShowHistory] = useState(false);
  return { attachmentCount, setAttachmentCount, fullscreen, setFullscreen, showHistory, setShowHistory };
}
