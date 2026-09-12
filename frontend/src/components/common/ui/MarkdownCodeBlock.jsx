import { isValidElement } from 'react';
import CodeBlock from './CodeBlock';

/** Текст узла кода: у блока это одна строка, но children приходит и массивом. */
const textOf = (value) => {
  if (Array.isArray(value)) return value.map(textOf).join('');
  return typeof value === 'string' ? value : '';
};

/**
 * `pre` для ReactMarkdown: превращает блок кода в `<CodeBlock>` и **заменяет**
 * собой `<pre>` разметки, а не вкладывается в него — свой `<pre>` есть у самого
 * CodeBlock, и вложенные друг в друга дали бы рамку в рамке.
 *
 * Блочность определяется здесь, а не в компоненте `code`, потому что `pre` —
 * единственный признак блока, который есть в разметке: пропа `inline`
 * react-markdown не передаёт, а «есть перевод строки» ошибается на однострочном
 * блоке без языка и оставляет его без шапки с языком и копированием.
 *
 * Строчный код при этом рисуется по умолчанию (своего `code` в наборе нет):
 * подменённому компоненту react-markdown отдаёт ещё и служебный проп `node`, и
 * спред пропсов уносил его на DOM-узел атрибутом `node="[object Object]"`.
 */
const MarkdownCodeBlock = ({ children }) => {
  const node = Array.isArray(children) ? children[0] : children;
  if (!isValidElement(node)) return <pre>{children}</pre>;
  // Хвостовой перевод строки есть у каждого блока — он часть заборчика, а не кода,
  // и не нужен ни в отрисованном тексте, ни в том, что уедет в буфер обмена.
  const code = textOf(node.props.children).replace(/\n$/, '');
  return (
    <CodeBlock code={code} className={node.props.className}>
      {code}
    </CodeBlock>
  );
};

export default MarkdownCodeBlock;
