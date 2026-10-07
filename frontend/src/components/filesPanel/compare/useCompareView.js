import { useMemo } from 'react';
import useComparison from './useComparison';

/**
 * Сравнение в панели «Файлы» целиком: ответ сервера (useComparison) для
 * списка слева и описание вкладки «Сравнение» справа — что в ней показать и
 * что делают её кнопки. `compareTab` — `null`, пока база не выбрана: тогда
 * вкладки нет вовсе.
 *
 * `branch` — текущая ветка рабочего дерева: ею называется сравниваемая
 * сторона, когда снимка нет (на отсоединённом HEAD — сам коммит из ответа).
 */
export default function useCompareView({
  enabled,
  project,
  path,
  rev,
  base,
  direct,
  branch,
  refreshToken,
  refsToken,
  onCompareChange,
  onPathChange,
}) {
  const comparison = useComparison({ project, base, rev, direct, refreshToken, refsToken, enabled });
  const headName = rev || branch || '';

  const compareTab = useMemo(
    () =>
      enabled
        ? {
            base,
            headName,
            direct,
            comparison: comparison.comparison,
            loading: comparison.loading,
            error: comparison.error,
            onDirectChange: (next) => onCompareChange(base, next),
            // Поменять местами — переход, а не правка экрана: меняется и
            // показанная ревизия, и «Назад» должно вернуть прежнее сравнение.
            onSwap: () =>
              onPathChange(path, undefined, {
                rev: base,
                base: headName || comparison.comparison?.head.hash || 'HEAD',
                direct,
              }),
            onExit: () => onCompareChange(''),
          }
        : null,
    [enabled, base, headName, direct, comparison, onCompareChange, onPathChange, path],
  );

  return { comparison, compareTab };
}
