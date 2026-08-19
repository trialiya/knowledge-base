import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { IconChevronDown } from '../../../../icons';
import CodeLines from './codeLines';

// Режим «Обзор» для секции аргументов: короткие значения строками, длинные —
// сворачиваемыми блоками с настоящими переносами.
//
// Разбор — в argumentList.js; сюда приходят уже разделённые поля и блоки.

// Имена аргументов не переводятся: это параметры сигнатуры инструмента, и
// сверять их с ней придётся ровно в том виде, в каком их прислала модель.

/** Длинный аргумент: строка-заголовок с объёмом, под ней — текст. */
const Block = ({ block, defaultOpen }) => {
  const { t } = useTranslation('chat');
  const [open, setOpen] = useState(defaultOpen);
  // Разворот текста переживает сворачивание блока: свернули, чтобы убрать с
  // глаз, а не чтобы отменить уже сделанное «показать целиком».
  const [expanded, setExpanded] = useState(false);

  return (
    <div className="tool-args__block">
      <button type="button" className="tool-args__block-head" onClick={() => setOpen((v) => !v)} aria-expanded={open}>
        <span className={`tool-args__chevron${open ? ' tool-args__chevron--open' : ''}`} aria-hidden="true">
          <IconChevronDown />
        </span>
        <span className="tool-args__key">{block.key}</span>
        <span className="tool-args__size">
          {t('toolCall.detail.args.chars', { count: block.chars })}
          {' · '}
          {t('toolCall.detail.args.lines', { count: block.lines.length })}
        </span>
      </button>
      {open && <CodeLines lines={block.lines} expanded={expanded} onExpand={() => setExpanded(true)} />}
    </div>
  );
};

const ArgumentListView = ({ data }) => (
  <div className="tool-args">
    {data.fields.length > 0 && (
      <dl className="tool-args__fields">
        {data.fields.map(({ key, value }) => (
          <div key={key} className="tool-args__field">
            <dt className="tool-args__key">{key}</dt>
            <dd className="tool-args__value">{value}</dd>
          </div>
        ))}
      </dl>
    )}

    {/* Блоки идут после строк, а не по месту в объекте: экран markdown между
        `path` и `limit` похоронил бы короткие аргументы под собой. */}
    {data.blocks.map((block) => (
      <Block key={block.key} block={block} defaultOpen={data.blocks.length === 1} />
    ))}
  </div>
);

export default ArgumentListView;
