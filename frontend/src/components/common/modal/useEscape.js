import { useEffect, useEffectEvent } from 'react';

/**
 * Calls `onEscape` whenever the Escape key is pressed while mounted.
 * Centralizes the keydown listener duplicated across modal components.
 *
 * `onEscape` may be a fresh inline callback on every render: it is wrapped in an
 * effect event, so the listener is registered once and still calls the latest
 * version. Callers must not memoize it into staleness themselves.
 */
export default function useEscape(onEscape) {
  const fire = useEffectEvent(() => onEscape());
  useEffect(() => {
    const onKey = (e) => {
      if (e.key === 'Escape') fire();
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, []);
}
