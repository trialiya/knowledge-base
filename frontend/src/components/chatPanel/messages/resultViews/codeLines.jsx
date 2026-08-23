import { useState } from 'react';
import { useTranslation } from 'react-i18next';

// Текст с номерами строк и порогом объёма. Общий блок для всего, что показывает
// содержимое настоящими переносами, а не строкой с `\n`: результат инструмента
// и длинный аргумент вызова.
//
// «Показать целиком» и «переносить строки» держит не этот компонент, а тот, кто
// его смонтировал: блок уходит с экрана от чужих переключателей — markdown
// вместо исходника, свёрнутая секция, — и собственное состояние он унёс бы с
// собой, молча вернув текст к порогу и к горизонтальной прокрутке, хотя
// раскрыть и развернуть его просили один раз. Состояние собирает
// `useCodeLinesView`.

/** Сколько строк показываем до нажатия «показать целиком». */
const LINE_CAP = 300;

/**
 * С какой длины строка считается длинной. Ниже этого порога переносить нечего —
 * и кнопки нет: тумблер над каждым куском кода читался бы как мусор.
 */
const WRAP_HINT_LEN = 120;

/** Состояние показа текста, живущее у владельца блока (см. комментарий выше). */
export const useCodeLinesView = () => {
  const [expanded, setExpanded] = useState(false);
  const [wrap, setWrap] = useState(false);
  return {
    expanded,
    onExpand: () => setExpanded(true),
    wrap,
    onToggleWrap: () => setWrap((v) => !v),
  };
};

const CodeLines = ({ lines, startLine = 1, expanded, onExpand, wrap = false, onToggleWrap }) => {
  const { t } = useTranslation('chat');

  const shown = expanded ? lines.length : Math.min(lines.length, LINE_CAP);
  const hidden = lines.length - shown;
  const canWrap = !!onToggleWrap && lines.some((line) => line.length > WRAP_HINT_LEN);

  return (
    <>
      {canWrap && (
        <div className="tool-code__bar">
          <button
            type="button"
            className="btn btn--ghost btn--sm"
            aria-pressed={wrap}
            onClick={onToggleWrap}
            title={t('toolCall.detail.wrapHint')}
          >
            {t('toolCall.detail.wrap')}
          </button>
        </div>
      )}
      <div className={`tool-code${wrap ? ' tool-code--wrap' : ''}`}>
        {lines.slice(0, shown).map((line, i) => (
          // Индекс как key безопасен: текст блока иммутабелен в рамках открытой модалки.

          <div key={i} className="tool-code__line">
            <span className="tool-code__line-no">{startLine + i}</span>
            <span className="tool-code__line-text">{line || ' '}</span>
          </div>
        ))}
      </div>
      {hidden > 0 && (
        <button type="button" className="btn btn--ghost btn--sm" onClick={onExpand}>
          {t('toolCall.detail.showAll', { count: hidden })}
        </button>
      )}
    </>
  );
};

export default CodeLines;
