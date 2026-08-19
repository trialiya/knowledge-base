import { useTranslation } from 'react-i18next';

// Текст с номерами строк и порогом объёма. Общий блок для всего, что показывает
// содержимое настоящими переносами, а не строкой с `\n`: результат инструмента
// и длинный аргумент вызова.
//
// «Показать целиком» держит не этот компонент, а тот, кто его смонтировал:
// блок уходит с экрана от чужих переключателей — markdown вместо исходника,
// свёрнутая секция, — и собственное состояние он унёс бы с собой, молча вернув
// текст к порогу, хотя раскрыть его просили один раз.

/** Сколько строк показываем до нажатия «показать целиком». */
const LINE_CAP = 300;

const CodeLines = ({ lines, startLine = 1, expanded, onExpand }) => {
  const { t } = useTranslation('chat');

  const shown = expanded ? lines.length : Math.min(lines.length, LINE_CAP);
  const hidden = lines.length - shown;

  return (
    <>
      <div className="tool-code">
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
