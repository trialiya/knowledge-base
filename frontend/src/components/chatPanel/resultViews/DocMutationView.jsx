import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import HistoryModal from '../../knowledgeBasePanel/HistoryModal';
import { formatFieldValue } from './fieldValue';

// Режим «Обзор» для формы «мутация документа»: карточка правки со ссылкой в
// историю версий — вместо девяти полей DTO, из которых человеку нужны три.
//
// Разбор ответа — в docMutation.js; сюда приходит уже готовая карточка.

const DocMutationView = ({ data }) => {
  const { t, i18n } = useTranslation('chat');
  const [history, setHistory] = useState(false);

  return (
    <div className="tool-doc">
      <div className="tool-doc__card">
        <span className="tool-doc__icon" aria-hidden="true">
          📄
        </span>
        <span className="tool-doc__text">
          <span className="tool-doc__title" title={data.title}>
            {data.title}
          </span>
          <span className="tool-doc__facts">
            {data.facts.map(({ key, value }) => (
              <span key={key} className="tool-doc__fact">
                {t(`toolCall.detail.fact.${key}`, { defaultValue: key })}: {formatFieldValue(key, value, i18n.language)}
              </span>
            ))}
          </span>
        </span>
        <button type="button" className="btn btn--ghost btn--sm" onClick={() => setHistory(true)}>
          {t('docChange.viewChanges')}
        </button>
      </div>

      {/* Полноэкранная модалка истории ложится поверх модалки вызова: стопку
          ModalShell ведёт сам, Escape закрывает только верхнюю. */}
      {history && (
        <HistoryModal
          documentId={data.id}
          documentTitle={data.title}
          initialVersion={data.descriptionVersion}
          tree={[]}
          onClose={() => setHistory(false)}
        />
      )}
    </div>
  );
};

export default DocMutationView;
