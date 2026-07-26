import React from 'react';
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
 */
const InfoList = ({ rows, note }) => {
  const visible = rows.filter((row) => row && row.value != null && row.value !== '');

  return (
    <div className="info-list">
      <dl className="info-list__list">
        {visible.map((row) => (
          <div className={`info-list__row${row.block ? ' info-list__row--block' : ''}`} key={row.label}>
            <dt className="info-list__label">{row.label}</dt>
            <dd className={`info-list__value${row.mono ? ' info-list__value--mono' : ''}`}>{row.value}</dd>
          </div>
        ))}
      </dl>
      {note && <p className="info-list__note">{note}</p>}
    </div>
  );
};

export default InfoList;
