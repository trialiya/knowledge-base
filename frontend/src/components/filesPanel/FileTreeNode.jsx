import React, { useEffect, useRef } from 'react';
import { useTranslation } from 'react-i18next';
import { IconFolder, IconDoc, IconChevron } from '../../icons';

const FileTreeNode = ({ node, level, selectedPath, expanded, treeCache, loadingDirs, onToggle, onSelect }) => {
  const { t } = useTranslation('files');
  const isDir = node.type === 'directory';
  const isOpen = expanded.has(node.path);
  const isSelected = node.path === selectedPath;
  const children = treeCache[node.path];
  const isLoading = loadingDirs.has(node.path);

  // Доскроллить дерево до выбранного узла: на глубокой вложенности (deep link,
  // либо клик на корневой элемент при уже проскроленном вправо дереве) он
  // оказывается за границей панели. Считаем вручную, а не через scrollIntoView:
  // строка (.file-tree-row) растянута на всю ширину раскрытого дерева
  // (шире вьюпорта), поэтому scrollIntoView на самой строке или на метке с
  // flex:1 «выравнивал по левому краю» и обрезал начало имени у мелких узлов.
  // Видимой должна быть область от шеврона/иконки (начало строки) до конца
  // имени — не вся растянутая строка и не только имя.
  const rowRef = useRef(null);
  const chevronRef = useRef(null);
  const labelRef = useRef(null);
  useEffect(() => {
    if (!isSelected) return;
    const row = rowRef.current;
    const start = chevronRef.current;
    const label = labelRef.current;
    const container = row?.closest('.files-panel-tree');
    if (!row || !start || !label || !container) return;

    const containerRect = container.getBoundingClientRect();

    // Вертикаль: строка целиком должна попасть в видимую область.
    const rowRect = row.getBoundingClientRect();
    if (rowRect.bottom > containerRect.bottom) {
      container.scrollTop += rowRect.bottom - containerRect.bottom;
    } else if (rowRect.top < containerRect.top) {
      container.scrollTop -= containerRect.top - rowRect.top;
    }

    // Горизонталь: минимальный сдвиг, чтобы уместились и шеврон/иконка, и имя.
    const startRect = start.getBoundingClientRect();
    const endRect = label.getBoundingClientRect();
    if (endRect.right > containerRect.right) {
      container.scrollLeft += endRect.right - containerRect.right;
    } else if (startRect.left < containerRect.left) {
      container.scrollLeft -= containerRect.left - startRect.left;
    }
  }, [isSelected]);

  return (
    <div className="file-tree-node-wrap">
      <div
        ref={rowRef}
        className={`file-tree-row ${isSelected ? 'file-tree-row--selected' : ''}`}
        style={{ '--depth': level }}
        onClick={() => {
          onSelect(node);
          if (isDir) onToggle(node.path);
        }}
      >
        <span
          ref={chevronRef}
          className="file-tree-row__chevron"
          onClick={(e) => {
            e.stopPropagation();
            if (isDir) onToggle(node.path);
          }}
        >
          {isDir ? <IconChevron open={isOpen} /> : <span className="file-tree-row__chevron-spacer" />}
        </span>
        <span className={`file-tree-row__icon ${isDir ? 'file-tree-row__icon--folder' : 'file-tree-row__icon--file'}`}>
          {isDir ? <IconFolder /> : <IconDoc />}
        </span>
        <span ref={labelRef} className="file-tree-row__label">
          {node.name}
        </span>
      </div>

      {isDir && isOpen && (
        <div className="file-tree-children">
          {!children && isLoading && (
            <div className="file-tree-hint" style={{ '--depth': level + 1 }}>
              {t('tree.loading')}
            </div>
          )}
          {children && children.length === 0 && (
            <div className="file-tree-hint" style={{ '--depth': level + 1 }}>
              {t('tree.empty')}
            </div>
          )}
          {children &&
            children.map((child) => (
              <FileTreeNode
                key={child.path}
                node={child}
                level={level + 1}
                selectedPath={selectedPath}
                expanded={expanded}
                treeCache={treeCache}
                loadingDirs={loadingDirs}
                onToggle={onToggle}
                onSelect={onSelect}
              />
            ))}
        </div>
      )}
    </div>
  );
};

export default FileTreeNode;
