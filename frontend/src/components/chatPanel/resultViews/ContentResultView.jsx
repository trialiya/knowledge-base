import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { formatFieldValue } from './fieldValue';
import CodeLines from './codeLines';

// Режим «Обзор» для текстовых результатов: содержимое файла, вложения или
// документа — настоящими переносами строк и с номерами, а не JSON-строкой,
// в которой они экранированы как \n.
//
// Разбор ответа — в contentResult.js; сюда приходят уже готовые блоки.

/** Факты блока + диапазон строк, посчитанный по самому тексту. */
const FactList = ({ item, lines }) => {
  const { t, i18n } = useTranslation('chat');
  const range =
    item.text === null
      ? null
      : item.startLine > 1
      ? `${item.startLine}–${item.startLine + lines.length - 1}`
      : String(lines.length);

  return (
    <div className="tool-result__facts">
      {item.title && (
        <span className="tool-result__facts-title" title={item.title}>
          {item.title}
        </span>
      )}
      {range && (
        <span className="tool-result__fact">
          {t('toolCall.detail.fact.lines')}: {range}
        </span>
      )}
      {item.facts.map(({ key, value }) => (
        <span key={key} className="tool-result__fact">
          {t(`toolCall.detail.fact.${key}`, { defaultValue: key })}: {formatFieldValue(key, value, i18n.language)}
        </span>
      ))}
    </div>
  );
};

/** Один текстовый блок: шапка фактов, переключатель markdown и сам текст. */
const ContentItem = ({ item }) => {
  const { t } = useTranslation('chat');
  const [rendered, setRendered] = useState(item.markdown);

  const lines = item.text === null ? [] : item.text.split('\n');

  return (
    <section className="tool-result__item">
      <div className="tool-result__item-head">
        <FactList item={item} lines={lines} />
        {item.markdown && item.text !== null && (
          <button
            type="button"
            className={`tool-result__md-toggle${rendered ? ' tool-result__md-toggle--active' : ''}`}
            onClick={() => setRendered((v) => !v)}
            title={t('fileChange.toggleMarkdown')}
          >
            {rendered ? '{ }' : '👁'}
          </button>
        )}
      </div>

      {item.binary && <div className="tool-result__empty">{t('fileChips.binaryFile')}</div>}

      {item.text !== null &&
        (rendered ? (
          <div className="tool-result__markdown">
            <ReactMarkdown remarkPlugins={[remarkGfm]}>{item.text}</ReactMarkdown>
          </div>
        ) : (
          <CodeLines lines={lines} startLine={item.startLine} />
        ))}
    </section>
  );
};

const ContentResultView = ({ data: items }) => (
  <div className="tool-result">
    {items.map((item) => (
      <ContentItem key={item.key} item={item} />
    ))}
  </div>
);

export default ContentResultView;
