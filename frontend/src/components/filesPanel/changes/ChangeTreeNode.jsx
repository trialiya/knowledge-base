import { IconFolder, IconChevron } from '@/icons/index';
import ChangeRow from './ChangeRow';

/**
 * Узел иерархии изменений: каталог со своими потомками либо строка файла.
 *
 * Каталоги раскрыты по умолчанию, а свёрнутые перечисляет вызывающий
 * (`collapsed`): набор изменений мал, и дерево, которое приходится раскрывать
 * руками, показало бы при открытии панели одни имена каталогов.
 */
const ChangeTreeNode = ({ node, level, selectedPath, collapsed, onToggle, onSelect, onDiscard }) => {
  if (node.type === 'file') {
    return (
      <ChangeRow
        entry={node.entry}
        role="treeitem"
        level={level + 1}
        depth={level}
        selected={node.path === selectedPath}
        onSelect={onSelect}
        onDiscard={onDiscard}
      />
    );
  }

  const isOpen = !collapsed.has(node.path);
  return (
    <div className="file-tree-node-wrap" role="none">
      <div
        data-ws-item
        role="treeitem"
        aria-expanded={isOpen}
        aria-level={level + 1}
        tabIndex={-1}
        className="ws-item"
        style={{ '--depth': level }}
        onClick={() => onToggle(node.path)}
      >
        <span className="ws-item__chevron" data-ws-chevron>
          <IconChevron open={isOpen} />
        </span>
        <span className="ws-item__icon ws-item__icon--folder">
          <IconFolder />
        </span>
        <span className="ws-item__label">{node.name}</span>
      </div>
      {isOpen && (
        <div className="file-tree-children" role="group">
          {node.children.map((child) => (
            <ChangeTreeNode
              key={child.path}
              node={child}
              level={level + 1}
              selectedPath={selectedPath}
              collapsed={collapsed}
              onToggle={onToggle}
              onSelect={onSelect}
              onDiscard={onDiscard}
            />
          ))}
        </div>
      )}
    </div>
  );
};

export default ChangeTreeNode;
