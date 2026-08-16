import { useTranslation } from 'react-i18next';
import { IconChevronDown } from '../../../icons';
import { formatFieldValue } from './fieldValue';
import ResultSummary, { useExpandAll } from './resultSummary';

// Режим «Обзор» для формы «дерево / оглавление»: узлы с отступом по глубине,
// сворачиваемые по клику.
//
// Разбор ответа — в treeResult.js; сюда приходят уже готовые узлы.

// Сколько узлов раскрыто сразу. Считается по уровням: открываем очередную
// глубину целиком, пока видимых узлов не станет больше бюджета — так у мелкого
// дерева видно всё, а у большого хотя бы верхние уровни.
const OPEN_NODE_BUDGET = 80;

/** Ключи узлов, раскрытых при первом показе. */
const initialOpen = (nodes) => {
  const open = {};
  let level = nodes;
  let visible = nodes.length;

  while (level.length > 0) {
    const next = level.flatMap((node) => node.children);
    if (visible + next.length > OPEN_NODE_BUDGET) break;
    level.forEach((node) => {
      if (node.children.length > 0) open[node.key] = true;
    });
    visible += next.length;
    level = next;
  }
  return open;
};

const collectKeys = (nodes) =>
  nodes.flatMap((node) => (node.children.length > 0 ? [node.key, ...collectKeys(node.children)] : []));

// Чип показывает только значение — подпись поля уехала в title: строка узла и
// так тесная, а «H2» и «40–730» без пояснения читаются, пока на них не
// понадобится навести.
const Chips = ({ items }) => {
  const { t, i18n } = useTranslation('chat');
  return items.map(({ key, value }) => (
    <span key={key} className="tool-tree__chip" title={t(`toolCall.detail.fact.${key}`, { defaultValue: key })}>
      {formatFieldValue(key, value, i18n.language)}
    </span>
  ));
};

const TreeNode = ({ node, level, expand }) => {
  const branch = node.children.length > 0;
  const open = expand.isOpen(node.key);

  return (
    <li className="tool-tree__item">
      {/* Кнопка и у листа тоже: иначе строки разъезжаются по левому краю на
          ширину шеврона, и колонка названий перестаёт быть колонкой. */}
      <button
        type="button"
        className="tool-tree__row"
        style={{ paddingLeft: `${level * 16 + 6}px` }}
        onClick={() => branch && expand.toggle(node.key)}
        aria-expanded={branch ? open : undefined}
        disabled={!branch}
      >
        <span
          className={`tool-tree__chevron${open ? ' tool-tree__chevron--open' : ''}${
            branch ? '' : ' tool-tree__chevron--leaf'
          }`}
          aria-hidden="true"
        >
          <IconChevronDown />
        </span>
        <span className="tool-tree__label" title={node.label}>
          {node.label}
        </span>
        {node.secondary && <span className="tool-tree__secondary">{node.secondary}</span>}
        <Chips items={node.meta} />
      </button>

      {branch && open && (
        <ul className="tool-tree__children">
          {node.children.map((child) => (
            <TreeNode key={child.key} node={child} level={level + 1} expand={expand} />
          ))}
        </ul>
      )}
    </li>
  );
};

const TreeResultView = ({ data }) => {
  const { t, i18n } = useTranslation('chat');
  const keys = collectKeys(data.nodes);
  const expand = useExpandAll(keys, () => initialOpen(data.nodes));

  return (
    <div className="tool-tree">
      <ResultSummary expand={keys.length > 1 ? expand : null}>
        {data.header?.label && <span className="tool-tree__header">{data.header.label}</span>}
        {data.header?.meta.map(({ key, value }) => (
          <span key={key} className="tool-tree__header-fact">
            {t(`toolCall.detail.fact.${key}`, { defaultValue: key })}: {formatFieldValue(key, value, i18n.language)}
          </span>
        ))}
        {!data.header && t('toolCall.detail.tree.nodes', { count: data.count })}
      </ResultSummary>

      <ul className="tool-tree__children tool-tree__children--root">
        {data.nodes.map((node) => (
          <TreeNode key={node.key} node={node} level={0} expand={expand} />
        ))}
      </ul>
    </div>
  );
};

export default TreeResultView;
