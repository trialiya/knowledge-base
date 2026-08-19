import { useState, useRef, useCallback, useEffect, useMemo } from 'react';
import gitApi from '../../api/gitApi';
import { readDir, readDirs, readExpanded, putDirs, putExpanded, ancestorsOf } from './fileTreeStore';

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
 *
 * `project` — репозиторий, который показывает панель. Внутри жизни хука он
 * постоянен: смену проекта FilesPanel делает перемонтированием (key), иначе
 * пришлось бы по отдельности сбрасывать дерево, раскрытые узлы, содержимое,
 * запросы в полёте и ключ ответа — пять сбросов вместо одного, и любой забытый
 * показал бы файлы прежнего репозитория.
 *
 * `refreshToken` — внешний сигнал «что-то в репозитории могло поменяться»
 * (правка файла инструментом чата, см. App.jsx): рост значения перезапускает
 * эффект открытия пути ниже, даже если сам `path` не изменился. Каталоги,
 * которые к этому моменту уже сброшены из кэша (invalidatePath), будут
 * перезапрошены как недостающие; сам открытый путь всегда перезапрашивается
 * заново вне зависимости от кэша.
 */
export default function useFileTree({ project, path, onPathChange, refreshToken }) {
  const [treeCache, setTreeCache] = useState(() => readDirs(project));
  // Каталоги, которые тянет ensureDir (раскрытие шевроном). Предки открываемого
  // пути сюда не попадают — их спиннеры выводятся ниже из самого запроса.
  const [expandingDirs, setExpandingDirs] = useState(() => new Set());
  // Предков открываемого пути раскрываем сразу на монтировании, не дожидаясь
  // ответа: уровни, уже лежащие в кэше, отрисуются мгновенно.
  const [expanded, setExpanded] = useState(() => {
    const stored = readExpanded(project);
    ancestorsOf(path).forEach((dir) => stored.add(dir));
    return stored;
  });
  const [content, setContent] = useState(null);

  const inFlightRef = useRef(new Map()); // dirPath -> Promise, dedups concurrent fetches

  // Кэш каталогов и раскрытые узлы переживают размонтирование панели.
  const cacheDirs = useCallback(
    (entries) => {
      putDirs(project, entries);
      setTreeCache((prev) => ({ ...prev, ...entries }));
    },
    [project],
  );
  useEffect(() => {
    putExpanded(project, expanded);
  }, [project, expanded]);

  const markLoading = useCallback((dirs, loading) => {
    if (dirs.length === 0) return;
    setExpandingDirs((prev) => {
      const next = new Set(prev);
      dirs.forEach((d) => (loading ? next.add(d) : next.delete(d)));
      return next;
    });
  }, []);

  const ensureDir = useCallback(
    (dirPath) => {
      // Спрашиваем сам кэш, а не состояние: `treeCache` — его снимок для
      // отрисовки, и зеркалить снимок обратно нечем.
      const cached = readDir(project, dirPath);
      if (cached) return Promise.resolve(cached);
      if (inFlightRef.current.has(dirPath)) {
        return inFlightRef.current.get(dirPath);
      }
      markLoading([dirPath], true);
      // Ошибку НЕ кэшируем в treeCache: иначе каталог навсегда застревает
      // «пустым» (неотличимо от реально пустой папки) без возможности повторить
      // запрос. Промис отклоняется, и следующий ensureDir(dirPath) (повторный
      // клик по шеврону) увидит, что в кэше ничего нет, и запросит заново.
      const promise = gitApi
        .getTree(dirPath, { project })
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
    [project, cacheDirs, markLoading],
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

  // Запрос открытия пути — это сам путь плюс внешний сигнал обновления.
  const contentKey = `${refreshToken ?? 0} ${path}`;
  // Ключ, ответ по которому уже получен. Отсюда и «Загрузка…» в центре:
  // отдельным состоянием он был бы setState в теле эффекта, то есть лишним
  // проходом рендера на каждую навигацию.
  const [answeredKey, setAnsweredKey] = useState(null);
  const contentLoading = answeredKey !== contentKey;

  const ancestors = useMemo(() => ancestorsOf(path), [path]);

  // Спиннеры каталогов: раскрытые шевроном плюс предки открываемого пути,
  // листингов которых ещё нет. Второй набор выводится, а не хранится — как
  // только листинг приходит в кэш, спиннер гаснет сам, и «перехват» общего
  // предка более новой навигацией получается бесплатно: набор всегда описывает
  // текущий путь, а не тот запрос, что его завёл.
  const loadingDirs = useMemo(() => {
    if (!contentLoading) return expandingDirs;
    const next = new Set(expandingDirs);
    ancestors.forEach((dir) => {
      if (!treeCache[dir]) next.add(dir);
    });
    return next;
  }, [contentLoading, expandingDirs, ancestors, treeCache]);

  // Предков раскрываем сразу, не дожидаясь ответа: те их уровни, что уже в
  // кэше, отрисуются мгновенно, остальные — по мере прихода листингов.
  const [prevContentKey, setPrevContentKey] = useState(contentKey);
  if (prevContentKey !== contentKey) {
    setPrevContentKey(contentKey);
    setExpanded((prev) => {
      if (ancestors.every((dir) => prev.has(dir))) return prev;
      const next = new Set(prev);
      ancestors.forEach((dir) => next.add(dir));
      return next;
    });
  }

  // Единый try/finally: ответ обязан отметиться на любом выходе (успех,
  // not-found, ошибка) — иначе в центре навсегда остаётся «Загрузка…».
  useEffect(() => {
    let cancelled = false;
    const missing = ancestorsOf(path).filter((dir) => !readDir(project, dir));

    (async () => {
      try {
        const view = await gitApi.browse(path, { ancestors: missing.length > 0, project });
        if (cancelled) return;

        const levels = Object.fromEntries((view.tree ?? []).map((level) => [level.path, level.nodes]));
        // Листинг открытого каталога кладём в кэш под его же путём — дерево
        // раскрывает выбранный узел и второй запрос за теми же данными не нужен.
        if (view.type === 'directory') {
          levels[view.path] = view.nodes ?? [];
          setExpanded((prev) => (prev.has(view.path) ? prev : new Set(prev).add(view.path)));
        }
        if (Object.keys(levels).length > 0) cacheDirs(levels);
        setContent(contentOf(view, path));
      } catch (error) {
        if (!cancelled) setContent({ type: 'error', path, error });
      } finally {
        if (!cancelled) setAnsweredKey(contentKey);
      }
    })();

    return () => {
      cancelled = true;
    };
  }, [contentKey, project, path, cacheDirs]);

  const selectNode = useCallback((node) => onPathChange(node.path), [onPathChange]);

  return { treeCache, loadingDirs, expanded, toggleExpand, content, contentLoading, selectNode };
}
