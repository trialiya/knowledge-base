import { useState, useRef, useCallback, useEffect } from 'react';
import gitApi from '../../api/gitApi';

/** Родительский каталог пути ('' для верхнеуровневых узлов). */
function parentOf(path) {
  const i = path.lastIndexOf('/');
  return i < 0 ? '' : path.slice(0, i);
}

/** Каталоги-предки пути (от корня), сам путь не включается. */
function ancestorsOf(path) {
  if (!path) return [];
  const parts = path.split('/');
  parts.pop();
  const dirs = [''];
  let acc = '';
  for (const part of parts) {
    acc = acc ? `${acc}/${part}` : part;
    dirs.push(acc);
  }
  return dirs;
}

/**
 * Владеет ленивым деревом файлов репозитория и содержимым, выбранным по `path`.
 *
 * Кэширует уже загруженные каталоги (treeCache: dirPath → GitFileNode[]) и
 * дедуплицирует параллельные запросы к одному каталогу. При смене `path`
 * подгружает все каталоги-предки (чтобы дерево слева раскрылось до выбранного
 * узла — это нужно и при клике, и при прямом переходе по ссылке) и определяет,
 * файл это или каталог, чтобы наполнить `content` для основной области.
 */
export default function useFileTree({ path, onPathChange }) {
  const [treeCache, setTreeCache] = useState({});
  const [loadingDirs, setLoadingDirs] = useState(() => new Set());
  const [expanded, setExpanded] = useState(() => new Set(['']));
  const [content, setContent] = useState(null);
  const [contentLoading, setContentLoading] = useState(false);

  const treeCacheRef = useRef(treeCache);
  treeCacheRef.current = treeCache;
  const inFlightRef = useRef(new Map()); // dirPath -> Promise, dedups concurrent fetches

  const ensureDir = useCallback((dirPath) => {
    if (treeCacheRef.current[dirPath]) {
      return Promise.resolve(treeCacheRef.current[dirPath]);
    }
    if (inFlightRef.current.has(dirPath)) {
      return inFlightRef.current.get(dirPath);
    }
    setLoadingDirs((prev) => new Set(prev).add(dirPath));
    const promise = gitApi
      .getTree(dirPath)
      .catch(() => [])
      .then((nodes) => {
        setTreeCache((prev) => ({ ...prev, [dirPath]: nodes }));
        return nodes;
      })
      .finally(() => {
        inFlightRef.current.delete(dirPath);
        setLoadingDirs((prev) => {
          const next = new Set(prev);
          next.delete(dirPath);
          return next;
        });
      });
    inFlightRef.current.set(dirPath, promise);
    return promise;
  }, []);

  const toggleExpand = useCallback(
    (dirPath) => {
      setExpanded((prev) => {
        const next = new Set(prev);
        if (next.has(dirPath)) next.delete(dirPath);
        else next.add(dirPath);
        return next;
      });
      ensureDir(dirPath);
    },
    [ensureDir],
  );

  // ── Разрешение выбранного пути: раскрыть предков, определить тип, загрузить
  // содержимое (листинг каталога либо текст файла). ──────────────────────────
  useEffect(() => {
    let cancelled = false;

    (async () => {
      const ancestors = ancestorsOf(path);
      for (const dir of ancestors) {
        // eslint-disable-next-line no-await-in-loop
        await ensureDir(dir);
      }
      if (cancelled) return;
      setExpanded((prev) => {
        const next = new Set(prev);
        ancestors.forEach((d) => next.add(d));
        return next;
      });

      if (!path) {
        setContentLoading(true);
        const nodes = await ensureDir('');
        if (cancelled) return;
        setContent({ type: 'directory', path: '', nodes });
        setContentLoading(false);
        return;
      }

      const parentDir = parentOf(path);
      const siblings = await ensureDir(parentDir);
      if (cancelled) return;
      const name = path.slice(path.lastIndexOf('/') + 1);
      const node = (siblings || []).find((n) => n.name === name);

      if (!node) {
        setContent({ type: 'not-found', path });
        return;
      }

      if (node.type === 'directory') {
        setContentLoading(true);
        setExpanded((prev) => new Set(prev).add(path));
        const nodes = await ensureDir(path);
        if (cancelled) return;
        setContent({ type: 'directory', path, nodes });
        setContentLoading(false);
        return;
      }

      setContentLoading(true);
      try {
        const file = await gitApi.getFileContent(path);
        if (cancelled) return;
        setContent({ type: 'file', path, file });
      } catch (error) {
        if (cancelled) return;
        setContent({ type: 'error', path, error });
      } finally {
        if (!cancelled) setContentLoading(false);
      }
    })();

    return () => {
      cancelled = true;
    };
  }, [path, ensureDir]);

  const selectNode = useCallback((node) => onPathChange(node.path), [onPathChange]);

  return { treeCache, loadingDirs, expanded, toggleExpand, content, contentLoading, selectNode };
}
