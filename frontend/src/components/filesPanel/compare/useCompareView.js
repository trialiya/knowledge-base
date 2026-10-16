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
 *
 * `scope` — файл или каталог, до которого сравнение сужено (`?scope=`,
 * ScopeChip над списком). Оно в адресе, а не в состоянии панели: панель
 * перемонтируется со сменой ревизии, и «Поменять местами» иначе теряло бы его.
 */
export default function useCompareView({
  enabled,
  project,
  path,
  rev,
  base,
  direct,
  scope,
  branch,
  refreshToken,
  refsToken,
  onCompareChange,
  onPathChange,
}) {
  const comparison = useComparison({ project, base, rev, direct, scope, refreshToken, refsToken, enabled });
  const headName = rev || branch || '';

  const compareTab = useMemo(
    () =>
      enabled
        ? {
            base,
            headName,
            direct,
            scope,
            comparison: comparison.comparison,
            loading: comparison.loading,
            error: comparison.error,
            onDirectChange: (next) => onCompareChange(base, next),
            // Поменять местами — переход, а не правка экрана: меняется и
            // показанная ревизия, и «Назад» должно вернуть прежнее сравнение.
            // Ветка остаётся веткой, как её и выбирали; рабочее дерево без
            // ветки (detached HEAD) становится хешем своего коммита, а не
            // `HEAD`, который после следующего коммита значил бы другое.
            // Кнопка есть только у загруженного сравнения, так что хеш известен.
            onSwap: () =>
              onPathChange(path, undefined, {
                rev: base,
                base: headName || comparison.comparison.head.hash,
                direct,
                scope,
              }),
            onExit: () => onCompareChange(''),
          }
        : null,
    [enabled, base, headName, direct, scope, comparison, onCompareChange, onPathChange, path],
  );

  return { comparison, compareTab };
}
