import React, { useState, useRef, useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import { IconCopySmall, IconCopied } from '../../icons';
import { COPY_DONE_MS } from '../../constants/ui';
import './infoList.css';

/**
 * Вкладка «Инфо» правой панели — общая для чата, базы знаний и файлов.
 *
 * Один и тот же вид метаданных во всех разделах: `label → value` списком,
 * плюс необязательная сноска под ним. Раньше это была вёрстка внутри
 * knowledgeBasePanel/DetailInfo (классы `detail-info__*`); теперь разметка и
 * стили живут здесь, а разделы поставляют только строки.
 *
 * Пустые значения (null / '' / undefined) отбрасываются: набор полей у разных
 * источников разный (дерево vs полный GET, файл vs каталог), и строк-прочерков
 * «—» в панели быть не должно.
 *
 * props:
 *   rows — [{ label, value, mono, block }]
 *          mono  — моноширинное значение (хеш, путь, id)
 *          block — значение под меткой и по левому краю: для длинного текста
 *                  (сообщение коммита) выключка вправо в 4 строки нечитаема
 *   note — узел под списком (предупреждение/пояснение), необязателен
 *
 * У каждой строки — кнопка копирования значения в буфер обмена: большинство
 * значений здесь (id, хеш, путь) для того и нужны, чтобы вставить их куда-то
 * ещё, а руками их не выделить — они не текст, а вёрстка списка.
 */
const InfoList = ({ rows, note }) => {
  const { t } = useTranslation();
  const visible = rows.filter((row) => row && row.value != null && row.value !== '');
  const [copiedLabel, setCopiedLabel] = useState(null);
  const timerRef = useRef(null);

  useEffect(() => () => clearTimeout(timerRef.current), []);

  const handleCopy = async (row) => {
    try {
      await navigator.clipboard.writeText(String(row.value));
      setCopiedLabel(row.label);
      clearTimeout(timerRef.current);
      timerRef.current = setTimeout(() => setCopiedLabel(null), COPY_DONE_MS);
    } catch {
      /* clipboard API недоступен в insecure context */
    }
  };

  return (
    <div className="info-list">
      <dl className="info-list__list">
        {visible.map((row) => {
          const copied = copiedLabel === row.label;
          return (
            <div className={`info-list__row${row.block ? ' info-list__row--block' : ''}`} key={row.label}>
              <dt className="info-list__label">{row.label}</dt>
              <dd className={`info-list__value${row.mono ? ' info-list__value--mono' : ''}`}>
                <span className="info-list__value-text">{row.value}</span>
                <button
                  type="button"
                  className={`icon-btn info-list__copy-btn${copied ? ' icon-btn--done' : ''}`}
                  onClick={() => handleCopy(row)}
                  title={copied ? t('copied') : t('copy')}
                  aria-label={copied ? t('copied') : t('copy')}
                >
                  {copied ? <IconCopied size={12} /> : <IconCopySmall size={12} />}
                </button>
              </dd>
            </div>
          );
        })}
      </dl>
      {note && <p className="info-list__note">{note}</p>}
    </div>
  );
};

export default InfoList;
