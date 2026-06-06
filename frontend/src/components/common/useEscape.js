import { useEffect } from 'react';

/**
 * Calls `onEscape` whenever the Escape key is pressed while mounted.
 * Centralizes the keydown listener duplicated across modal components.
 */
export default function useEscape(onEscape) {
  useEffect(() => {
    const onKey = (e) => {
      if (e.key === 'Escape') onEscape();
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [onEscape]);
}
