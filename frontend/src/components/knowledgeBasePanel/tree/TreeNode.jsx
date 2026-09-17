import { useState, useEffect, useRef, useCallback, useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import { IconFolder, IconDoc, IconChevron, IconLock, IconDragHandle, IconTrash } from '@/icons/index';
import { findNodeById } from '@/components/common/ui/utils';
import { clientBox, revealVertically } from '@/components/common/layout/treeScroll';
import useNodeDrag from './useNodeDrag';
import { KB_PAGE_SIZE as PAGE_SIZE } from '@/constants/pagination';

const DragHandle = ({ disabled }) => {
  const { t } = useTranslation('knowledgeBase');
  return disabled ? (
    <span className="tree-row__drag-handle tree-row__drag-handle--disabled" aria-hidden="true" />
  ) : (
    <span className="tree-row__drag-handle" title={t('tree.dragToReorder')}>
      <IconDragHandle />
    </span>
  );
};

const TreeNode = ({ node, level, selectedId, onSelect, onDelete, onReorder, onLoadChildren }) => {
  const { t } = useTranslation('knowledgeBase');
  const isFolder = node.type === 'folder';
  const isSystem = !!node.system;
  const hasChildren = isFolder && (node.hasChildren || (node.children && node.children.length > 0));
  const childrenLoaded = node._childrenLoaded || (node.children && node.children.length > 0);
  const isSelected = node.id === selectedId;
  // Пометку _openOnLoad можно исполнить сразу, только когда дети уже есть:
  // иначе их сперва догружает эффект ниже.
  const needsChildLoad = isFolder && !childrenLoaded && !!onLoadChildren;
  // Мемо обязательно: без него каждый узел обходил бы своё поддерево на каждый
  // рендер дерева, а узлов столько же, сколько строк в панели.
  const isAncestorOfSelected = useMemo(
    () => isFolder && !!node.children && !!findNodeById(node.children, selectedId),
    [isFolder, node.children, selectedId],
  );

  // Начальную раскрытость считаем сразу, а не эффектом после первой отрисовки:
  // и предок выбранного узла, и узел, помеченный раскрыться по прямой ссылке,
  // должны быть развёрнуты уже в первом кадре, иначе дерево дёргается.
  const [open, setOpen] = useState(() => isAncestorOfSelected || (!!node._openOnLoad && !needsChildLoad));
  const [totalElements, setTotalElements] = useState(node._totalChildren ?? null);
  const [loadingMore, setLoadingMore] = useState(false);
  const rowRef = useRef(null);
  const { dropPos, dragHandlers } = useNodeDrag({ node, isFolder, isSystem, rowRef, onReorder });

  // Страница, которую дочитываем, — с недобором (floor, не ceil): длина списка
  // перестаёт быть кратной странице, как только узел ушёл из папки или пришёл в
  // неё перетаскиванием, и округление вверх перескакивало бы через ещё не
  // прочитанные строки. Перечитанных детей отсеивает по id spliceChildren.
  // Значение выводится из узла — при перезагрузке с нулевой страницы оно само
  // возвращается к началу.
  const nextPage = Math.floor((node.children?.length ?? 0) / PAGE_SIZE);

  // Ниже — реакции на изменившиеся пропы. Все в рендере, а не в эффектах: в
  // дереве это setState на каждом узле, то есть лишний проход рендера целиком.

  // Общее число детей кладёт в узел KnowledgeBase; его же обновляют ответы
  // догрузки страниц, поэтому оно и состояние, и синхронизируемое значение.
  const [prevTotal, setPrevTotal] = useState(node._totalChildren);
  if (prevTotal !== node._totalChildren) {
    setPrevTotal(node._totalChildren);
    if (node._totalChildren != null) setTotalElements(node._totalChildren);
  }

  // Выбор ушёл внутрь этой папки — раскрываем её.
  const [prevSelectedId, setPrevSelectedId] = useState(selectedId);
  if (prevSelectedId !== selectedId) {
    setPrevSelectedId(selectedId);
    if (isAncestorOfSelected) setOpen(true);
  }

  // Пометка «раскрыться», когда дети уже загружены. Ловим её по изменению
  // значения, а не по истине: пометку ставят и повторно (см. markOpenOnLoad).
  const [prevOpenOnLoad, setPrevOpenOnLoad] = useState(node._openOnLoad);
  if (prevOpenOnLoad !== node._openOnLoad) {
    setPrevOpenOnLoad(node._openOnLoad);
    if (node._openOnLoad && !open && !needsChildLoad) setOpen(true);
  }

  // Доскроллить панель до выбранного узла: по ссылке на документ предки
  // раскрываются сами, и узел оказывается сколь угодно далеко внизу. Только
  // вертикаль — горизонтальной прокрутки у панели нет, длинное имя обрезается
  // многоточием, начало его видно всегда.
  useEffect(() => {
    if (!isSelected) return;
    const row = rowRef.current;
    const container = row?.closest('.workspace__side-body');
    // Раздел смонтирован всегда (скрыт стилями): пока он скрыт, мерить нечего.
    if (!row || !container || !container.clientHeight) return;
    container.scrollTop = revealVertically(clientBox(container), row.getBoundingClientRect(), container.scrollTop);
  }, [isSelected]);

  // Та же пометка, но детей ещё нет: сперва догрузка, раскрытие — по её ответу.
  useEffect(() => {
    if (!node._openOnLoad || open || !needsChildLoad) return;
    onLoadChildren(node.id, 0, PAGE_SIZE).then((paged) => {
      if (paged?.totalElements != null) setTotalElements(paged.totalElements);
      setOpen(true);
    });
  }, [node._openOnLoad]); // eslint-disable-line react-hooks/exhaustive-deps

  const loadFirstPage = useCallback(async () => {
    const paged = await onLoadChildren(node.id, 0, PAGE_SIZE);
    if (paged?.totalElements != null) setTotalElements(paged.totalElements);
    return paged;
  }, [onLoadChildren, node.id]);

  const toggleOpen = useCallback(
    async (e) => {
      if (e) e.stopPropagation();
      if (!open && isFolder && !childrenLoaded && onLoadChildren) await loadFirstPage();
      setOpen((o) => !o);
    },
    [open, isFolder, childrenLoaded, onLoadChildren, loadFirstPage],
  );

  // Used by the row click (which also selects the node). It does NOT fetch:
  // the panel keeps a useFolderChildren on the selected node (KnowledgeBase.jsx),
  // and that one loads the full child list through the shared (deduplicated)
  // loader, splicing it into this same tree node. Firing a second PAGE_SIZE fetch
  // here would just duplicate that request (the size=10 + size=1000 pair). While
  // that answer is on its way the open folder shows a note row, not an empty group.
  const toggleOpenVisual = useCallback(() => {
    setOpen((o) => !o);
  }, []);

  const handleLoadMore = useCallback(
    async (e) => {
      e.stopPropagation();
      if (loadingMore || !onLoadChildren) return;
      setLoadingMore(true);
      try {
        const paged = await onLoadChildren(node.id, nextPage, PAGE_SIZE);
        if (paged?.totalElements != null) setTotalElements(paged.totalElements);
      } finally {
        setLoadingMore(false);
      }
    },
    [loadingMore, onLoadChildren, node.id, nextPage],
  );

  // ── Render ────────────────────────────────────────────────────────────────

  const dropClass =
    dropPos === 'before'
      ? 'tree-row--drop-before'
      : dropPos === 'after'
      ? 'tree-row--drop-after'
      : dropPos === 'inside'
      ? 'tree-row--drop-inside'
      : '';

  // Show "load more" when we know total and have loaded fewer
  const knownTotal = totalElements ?? node._totalChildren ?? null;
  const currentCount = node.children?.length ?? 0;
  const showLoadMore = open && isFolder && knownTotal !== null && currentCount < knownTotal;
  const remaining = knownTotal !== null ? knownTotal - currentCount : 0;

  return (
    // role="none" — обёртка нужна только для раскладки; без неё treeitem
    // оказывается не прямым потомком tree/group, и структура дерева для
    // скринридера разваливается.
    <div className="tree-node-wrap" role="none">
      <div
        ref={rowRef}
        data-ws-item
        role="treeitem"
        aria-selected={isSelected}
        aria-expanded={hasChildren ? open : undefined}
        aria-level={level + 1}
        tabIndex={-1}
        className={`ws-item tree-row ${isSelected ? 'ws-item--active' : ''} ${dropClass} ${
          isSystem ? 'tree-row--system' : ''
        }`}
        style={{ '--depth': level }}
        draggable={!isSystem}
        {...dragHandlers}
        onClick={() => {
          onSelect(node);
          if (isFolder) toggleOpenVisual();
        }}
      >
        <DragHandle disabled={isSystem} />

        <span className="ws-item__chevron" data-ws-chevron onClick={(e) => toggleOpen(e)}>
          {hasChildren && <IconChevron open={open} />}
        </span>

        <span className={`ws-item__icon${isFolder ? ' ws-item__icon--folder' : ''}`}>
          {isFolder ? <IconFolder /> : <IconDoc />}
        </span>

        <span className="ws-item__label">{node.title}</span>

        <span className="ws-item__actions">
          {isSystem ? (
            <span className="tree-row__system-badge" title={t('detail.systemBadge')}>
              <IconLock />
            </span>
          ) : (
            <button
              type="button"
              className="icon-btn icon-btn--danger ws-item__action"
              title={t('tree.delete')}
              aria-label={t('tree.delete')}
              onClick={(e) => {
                e.stopPropagation();
                onDelete(node.id);
              }}
            >
              <IconTrash size={12} />
            </button>
          )}
        </span>
      </div>

      {hasChildren && open && (
        <div className="tree-children" role="group">
          {/*
            Раскрыть папку можно раньше, чем придут её дети: кликом по строке.
            Пустой группой это выглядело бы как «в папке ничего нет», а отказ
            загрузки не выглядел бы никак — поэтому пока детей нет, на их месте
            стоит заметка, а на отказе она же и есть кнопка повторить.
          */}
          {!childrenLoaded &&
            (node._childrenError ? (
              <button
                type="button"
                className="tree-note tree-note--error"
                data-ws-item
                role="treeitem"
                aria-level={level + 2}
                aria-selected={false}
                tabIndex={-1}
                style={{ '--depth': level + 1 }}
                onClick={(e) => {
                  e.stopPropagation();
                  loadFirstPage();
                }}
              >
                {t('tree.childrenError')}
              </button>
            ) : (
              <div
                className="tree-note"
                role="treeitem"
                aria-level={level + 2}
                aria-selected={false}
                tabIndex={-1}
                style={{ '--depth': level + 1 }}
              >
                {t('tree.childrenLoading')}
              </div>
            ))}

          {node.children?.map((child) => (
            <TreeNode
              key={child.id}
              node={child}
              level={level + 1}
              selectedId={selectedId}
              onSelect={onSelect}
              onDelete={onDelete}
              onReorder={onReorder}
              onLoadChildren={onLoadChildren}
            />
          ))}

          {/* "Load more" trigger */}
          {showLoadMore && (
            <button
              className="tree-load-more"
              data-ws-item
              role="treeitem"
              aria-level={level + 2}
              aria-selected={false} // строка-действие, а не узел дерева — выбрать её нельзя
              // Как и остальные строки: в таб-порядке дерева одна точка входа —
              // сам контейнер, до строк добираются стрелками (useListNavigation).
              // Enter/Space здесь отрабатывает браузер — это настоящая кнопка.
              tabIndex={-1}
              style={{ '--depth': level + 1 }}
              onClick={handleLoadMore}
              disabled={loadingMore}
            >
              {loadingMore ? '…' : t('tree.loadMore', { count: remaining })}
            </button>
          )}
        </div>
      )}
    </div>
  );
};

export default TreeNode;
