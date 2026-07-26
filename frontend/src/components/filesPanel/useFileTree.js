import { useState, useRef, useCallback, useEffect } from 'react';
import gitApi from '../../api/gitApi';
import { readDirs, readExpanded, putDirs, putExpanded } from './fileTreeStore';

/** Каталоги-предки пути (от корня), сам путь не включается. */
function ancestorsOf(path) {
  const dirs = [''];
  if (!path) return dirs;
  for (let slash = path.indexOf('/'); slash >= 0; slash = path.indexOf('/', slash + 1)) {
    dirs.push(path.slice(0, slash));
  }
  return dirs;
}

/** Ответ /api/git/browse → содержимое центра ({ type, path, file|nodes }). */
function contentOf(view, path) {
  if (view.type === 'file') return { type: 'file', path: view.path, file: view.file };
  if (view.type === 'directory') return { type: 'directory', path: view.path, nodes: view.nodes ?? [] };
  return { type: 'not-found', path };
}

/**
 * Владеет ленивым деревом файлов репозитория и содержимым, выбранным по `path`.
 *
 * Открытие пути — **один** запрос `/api/git/browse`: бэкенд сам определяет, файл
 * это или каталог, отдаёт содержимое (или листинг) и заодно листинги всех
 * каталогов-предков, чтобы дерево слева раскрылось до узла. Раньше клиент шёл к
 * `/tree` по одному уровню вложенности — не зная типа пути, иначе было не
 * определить, — и только потом запрашивал содержимое: на пути вида
 * `a/b/c/d/e/f/File.java` это десяток последовательных round-trip'ов, около
 * секунды до первой отрисовки центра.
 *
 * Листинги предков не запрашиваются, если они уже в кэше (`ancestors=false`) —
 * это обычный случай навигации кликом по уже загруженному дереву. Сам кэш живёт
 * в модуле (см. fileTreeStore), поэтому переживает уход в другой раздел.
 *
 * Одиночный `/tree` остаётся для раскрытия каталога шевроном (ensureDir).
 */
export default function useFileTree({ path, onPathChange }) {
  const [treeCache, setTreeCache] = useState(readDirs);
  const [loadingDirs, setLoadingDirs] = useState(() => new Set());
  const [expanded, setExpanded] = useState(readExpanded);
  const [content, setContent] = useState(null);
  const [contentLoading, setContentLoading] = useState(true);

  const treeCacheRef = useRef(treeCache);
  treeCacheRef.current = treeCache;
  const inFlightRef = useRef(new Map()); // dirPath -> Promise, dedups concurrent fetches

  // Кэш каталогов и раскрытые узлы переживают размонтирование панели.
  const cacheDirs = useCallback((entries) => {
    putDirs(entries);
    setTreeCache((prev) => ({ ...prev, ...entries }));
  }, []);
  useEffect(() => {
    putExpanded(expanded);
  }, [expanded]);

  const markLoading = useCallback((dirs, loading) => {
    if (dirs.length === 0) return;
    setLoadingDirs((prev) => {
      const next = new Set(prev);
      dirs.forEach((d) => (loading ? next.add(d) : next.delete(d)));
      return next;
    });
  }, []);

  const ensureDir = useCallback(
    (dirPath) => {
      if (treeCacheRef.current[dirPath]) {
        return Promise.resolve(treeCacheRef.current[dirPath]);
      }
      if (inFlightRef.current.has(dirPath)) {
        return inFlightRef.current.get(dirPath);
      }
      markLoading([dirPath], true);
      // Ошибку НЕ кэшируем в treeCache: иначе каталог навсегда застревает
      // «пустым» (неотличимо от реально пустой папки) без возможности повторить
      // запрос. Промис отклоняется, и следующий ensureDir(dirPath) (повторный
      // клик по шеврону) увидит, что в кэше ничего нет, и запросит заново.
      const promise = gitApi
        .getTree(dirPath)
        .then((nodes) => {
          cacheDirs({ [dirPath]: nodes });
          return nodes;
        })
        .finally(() => {
          inFlightRef.current.delete(dirPath);
          markLoading([dirPath], false);
        });
      inFlightRef.current.set(dirPath, promise);
      return promise;
    },
    [cacheDirs, markLoading],
  );

  const toggleExpand = useCallback(
    (dirPath) => {
      setExpanded((prev) => {
        const next = new Set(prev);
        if (next.has(dirPath)) next.delete(dirPath);
        else next.add(dirPath);
        return next;
      });
      ensureDir(dirPath).catch(() => {});
    },
    [ensureDir],
  );

  // ── Открытие выбранного пути одним запросом ────────────────────────────────
  // Единый try/finally: contentLoading обязан сброситься на любом выходе
  // (успех, not-found, ошибка) — иначе в центре навсегда остаётся «Загрузка…».
  useEffect(() => {
    let cancelled = false;
    const ancestors = ancestorsOf(path);
    const missing = ancestors.filter((dir) => !treeCacheRef.current[dir]);
    setContentLoading(true);
    // Предков раскрываем сразу, не дожидаясь ответа: те их уровни, что уже в
    // кэше, отрисуются мгновенно, остальные — по мере прихода листингов.
    setExpanded((prev) => {
      const next = new Set(prev);
      ancestors.forEach((dir) => next.add(dir));
      return next;
    });
    markLoading(missing, true);

    (async () => {
      try {
        const view = await gitApi.browse(path, missing.length > 0);
        if (cancelled) return;

        const levels = Object.fromEntries((view.tree ?? []).map((level) => [level.path, level.nodes]));
        // Листинг открытого каталога кладём в кэш под его же путём — дерево
        // раскрывает выбранный узел и второй запрос за теми же данными не нужен.
        if (view.type === 'directory') {
          levels[view.path] = view.nodes ?? [];
          setExpanded((prev) => new Set(prev).add(view.path));
        }
        if (Object.keys(levels).length > 0) cacheDirs(levels);
        setContent(contentOf(view, path));
      } catch (error) {
        if (!cancelled) setContent({ type: 'error', path, error });
      } finally {
        // Отметку загрузки снимаем всегда, даже если запрос уже неактуален:
        // иначе брошенный при быстром переходе каталог навсегда остаётся со
        // спиннером. А contentLoading — только для актуального запроса, его
        // уже успел выставить эффект следующего пути.
        markLoading(missing, false);
        if (!cancelled) setContentLoading(false);
      }
    })();

    return () => {
      cancelled = true;
    };
  }, [path, cacheDirs, markLoading]);

  const selectNode = useCallback((node) => onPathChange(node.path), [onPathChange]);

  return { treeCache, loadingDirs, expanded, toggleExpand, content, contentLoading, selectNode };
}
